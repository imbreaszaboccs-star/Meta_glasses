"""Relay between the glasses web app (app menu on Meta Ray-Ban Display) and the phone app.

    web app  --POST /remote/web/command-->  relay  --SSE /remote/phone/events-->  phone
    web app  <--GET /remote/web/state----   relay  <--POST /remote/phone/state--  phone

Pairing: the phone (holding APP_TOKEN) gets a device id + phone secret and a one-time 8-char code
(10 min). The code is embedded in the web-app install URL; the web app trades it once for a
long-lived web token. Secrets/tokens/codes are stored only as SHA-256 hashes (SQLite).

Live state (phone status, today's notes/cards) and the command queue are in memory only and never
written to disk or logs. ponytail: single process (uvicorn default, 1 worker). Upgrade path for
several workers: move `Hub` to Redis pub/sub.
"""

import asyncio
import hashlib
import json
import os
import secrets
import sqlite3
import threading
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, field
from functools import lru_cache
from typing import Annotated, Literal
from urllib.parse import quote

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from ai import clean

CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"  # no 0/O, 1/I/L
CODE_LEN = 8
CODE_TTL_S = 600
STATE_MAX_BYTES = 64 * 1024
ONLINE_GRACE_S = 60  # phone counts as online this long after its last contact
SSE_PING_S = 20
SSE_MAX_S = 300  # server closes the stream, the phone reconnects (keeps proxies happy)
CLAIM_PER_MIN = 10


def _hash(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()


# ---------------------------------------------------------------- persistent pairing store


class Store:
    def __init__(self, path: str):
        if path != ":memory:":
            os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.lock = threading.Lock()
        with self.lock:
            self.db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, created REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS codes (code_hash TEXT PRIMARY KEY, device_id TEXT NOT NULL, expires REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS web_tokens (token_hash TEXT PRIMARY KEY, device_id TEXT NOT NULL, created REAL NOT NULL);
            """)

    def _one(self, sql: str, *args):
        with self.lock:
            return self.db.execute(sql, args).fetchone()

    def _run(self, sql: str, *args) -> None:
        with self.lock:
            self.db.execute(sql, args)

    def phone_ok(self, device_id: str, secret: str) -> bool:
        row = self._one("SELECT secret_hash FROM devices WHERE id = ?", device_id)
        return row is not None and secrets.compare_digest(row[0], _hash(secret))

    def new_device(self) -> tuple[str, str]:
        device_id, secret = secrets.token_hex(16), secrets.token_urlsafe(32)
        self._run("INSERT INTO devices VALUES (?, ?, ?)", device_id, _hash(secret), time.time())
        return device_id, secret

    def new_code(self, device_id: str, now: float) -> str:
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LEN))
        with self.lock:  # one live code per device
            self.db.execute("DELETE FROM codes WHERE device_id = ? OR expires < ?", (device_id, now))
            self.db.execute("INSERT INTO codes VALUES (?, ?, ?)", (_hash(code), device_id, now + CODE_TTL_S))
        return code

    def claim(self, code: str, now: float) -> str | None:
        """Single use: the code is deleted whether or not it was still valid."""
        with self.lock:
            row = self.db.execute("SELECT device_id, expires FROM codes WHERE code_hash = ?", (_hash(code),)).fetchone()
            if row is None:
                return None
            self.db.execute("DELETE FROM codes WHERE code_hash = ?", (_hash(code),))
            if row[1] < now:
                return None
            token = secrets.token_urlsafe(32)
            self.db.execute("INSERT INTO web_tokens VALUES (?, ?, ?)", (_hash(token), row[0], now))
            return token

    def device_for_web(self, token: str) -> str | None:
        row = self._one("SELECT device_id FROM web_tokens WHERE token_hash = ?", _hash(token))
        return row[0] if row else None

    def unpair(self, device_id: str) -> None:
        with self.lock:
            for table, col in (("web_tokens", "device_id"), ("codes", "device_id"), ("devices", "id")):
                self.db.execute(f"DELETE FROM {table} WHERE {col} = ?", (device_id,))


@lru_cache
def get_store() -> Store:
    return Store(os.path.join(os.environ.get("FOCUSLENS_DATA_DIR", "data"), "remote.db"))


# ---------------------------------------------------------------- in-memory live hub


@dataclass
class Live:
    queue: asyncio.Queue = field(default_factory=lambda: asyncio.Queue(maxsize=50))
    streams: int = 0
    last_seen: float = 0.0
    state: dict | None = None
    state_at: float = 0.0
    acks: deque = field(default_factory=lambda: deque(maxlen=10))

    def online(self, now: float) -> bool:
        return self.streams > 0 or now - self.last_seen < ONLINE_GRACE_S


class Hub:
    def __init__(self, clock: Callable[[], float] = time.time):
        self.clock = clock
        self.devices: dict[str, Live] = {}
        self.claims: dict[str, deque] = {}

    def live(self, device_id: str) -> Live:
        return self.devices.setdefault(device_id, Live())

    def claim_allowed(self, ip: str) -> bool:
        now = self.clock()
        q = self.claims.setdefault(ip, deque())
        while q and now - q[0] > 60:
            q.popleft()
        if len(q) >= CLAIM_PER_MIN:
            return False
        q.append(now)
        return True


@lru_cache
def get_hub() -> Hub:
    return Hub()


# ---------------------------------------------------------------- commands (whitelist)

Mode = Literal["auto", "lecture", "homework", "desk"]
ItemId = Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{1,64}$")]


class _Args(BaseModel):
    model_config = ConfigDict(extra="forbid")


class StartArgs(_Args):
    mode: Mode | None = None
    goal: str | None = Field(default=None, max_length=1000)

    @field_validator("goal")
    @classmethod
    def _goal(cls, v: str | None) -> str | None:
        return clean(v, 200) if v is not None else None


class ConfigArgs(_Args):
    mode: Mode | None = None
    nudges: bool | None = None
    wakeWord: bool | None = None
    readAloud: bool | None = None


class InboxDoneArgs(_Args):
    id: ItemId
    done: bool


class InboxIdArgs(_Args):
    id: ItemId


COMMANDS: dict[str, type[_Args]] = {
    "start_focus": StartArgs, "stop_focus": _Args, "help": _Args, "pause_mic": _Args, "resume_mic": _Args,
    "set_config": ConfigArgs, "inbox_done": InboxDoneArgs, "inbox_delete": InboxIdArgs,
    "review_load": _Args, "refresh": _Args,
}


class CommandIn(BaseModel):
    type: str
    args: dict = Field(default_factory=dict)


class ClaimIn(BaseModel):
    code: str = Field(min_length=CODE_LEN, max_length=CODE_LEN)


class PairIn(BaseModel):
    device_id: str | None = Field(default=None, max_length=64)
    phone_secret: str | None = Field(default=None, max_length=128)


class AckIn(BaseModel):
    id: str = Field(max_length=64)
    ok: bool
    error: str | None = Field(default=None, max_length=1000)


# ---------------------------------------------------------------- auth dependencies


def phone_auth(
    x_device_id: Annotated[str, Header()] = "",
    authorization: Annotated[str, Header()] = "",
    store: Store = Depends(get_store),
    hub: Hub = Depends(get_hub),
) -> str:
    secret = authorization.removeprefix("Bearer ").strip()
    if not x_device_id or not secret or not store.phone_ok(x_device_id, secret):
        raise HTTPException(401, "bad device credentials")
    hub.live(x_device_id).last_seen = hub.clock()
    return x_device_id


def web_auth(authorization: Annotated[str, Header()] = "", store: Store = Depends(get_store)) -> str:
    token = authorization.removeprefix("Bearer ").strip()
    device_id = store.device_for_web(token) if token else None
    if device_id is None:
        raise HTTPException(401, "bad web token")
    return device_id


# ---------------------------------------------------------------- routes


def create_router(app_auth: Callable, public_url: Callable[[], str]) -> APIRouter:
    """app_auth = the APP_TOKEN dependency (only our phone app may create devices);
    public_url = dependency returning the public base URL of this server."""
    r = APIRouter(prefix="/remote")

    @r.post("/pair", dependencies=[Depends(app_auth)])
    def pair(body: PairIn, store: Store = Depends(get_store), hub: Hub = Depends(get_hub),
             base: str = Depends(public_url)) -> dict:
        if body.device_id and body.phone_secret and store.phone_ok(body.device_id, body.phone_secret):
            device_id, secret = body.device_id, body.phone_secret
        else:
            device_id, secret = store.new_device()
        code = store.new_code(device_id, hub.clock())
        web_url = f"{base.rstrip('/')}/glasses/?code={code}"
        install_url = ("fb-viewapp://web_app_deep_link?appName=FocusLens&appUrl=" + quote(web_url, safe=""))
        return {"device_id": device_id, "phone_secret": secret, "code": code, "expires_in": CODE_TTL_S,
                "web_url": web_url, "install_url": install_url}

    @r.post("/unpair", status_code=204)
    def unpair(device_id: str = Depends(phone_auth), store: Store = Depends(get_store),
               hub: Hub = Depends(get_hub)) -> Response:
        store.unpair(device_id)
        hub.devices.pop(device_id, None)
        return Response(status_code=204)

    @r.post("/claim")
    def claim(body: ClaimIn, request: Request, store: Store = Depends(get_store), hub: Hub = Depends(get_hub)) -> dict:
        if not hub.claim_allowed(request.client.host if request.client else "?"):
            raise HTTPException(429, "too many attempts")
        token = store.claim(body.code.upper(), hub.clock())
        if token is None:
            raise HTTPException(404, "code invalid or expired")
        return {"web_token": token}

    @r.get("/phone/events")
    async def events(request: Request, device_id: str = Depends(phone_auth), hub: Hub = Depends(get_hub)):
        live = hub.live(device_id)

        async def stream():
            live.streams += 1
            try:
                yield ": hello\n\n"
                end = time.monotonic() + SSE_MAX_S
                while time.monotonic() < end and not await request.is_disconnected():
                    try:
                        cmd = await asyncio.wait_for(live.queue.get(), timeout=SSE_PING_S)
                    except TimeoutError:
                        live.last_seen = hub.clock()
                        yield ": ping\n\n"
                        continue
                    yield f"event: command\ndata: {json.dumps(cmd, ensure_ascii=False)}\n\n"
            finally:
                live.streams -= 1
                live.last_seen = hub.clock()

        return StreamingResponse(stream(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @r.post("/phone/state", status_code=204)
    async def phone_state(request: Request, device_id: str = Depends(phone_auth), hub: Hub = Depends(get_hub)) -> Response:
        body = await request.body()
        if len(body) > STATE_MAX_BYTES:
            raise HTTPException(413, "state too large")
        try:
            state = json.loads(body)
        except ValueError as e:
            raise HTTPException(422, "bad json") from e
        if not isinstance(state, dict):
            raise HTTPException(422, "state must be an object")
        live = hub.live(device_id)
        live.state, live.state_at = state, hub.clock()
        return Response(status_code=204)

    @r.post("/phone/ack", status_code=204)
    def phone_ack(body: AckIn, device_id: str = Depends(phone_auth), hub: Hub = Depends(get_hub)) -> Response:
        error = clean(body.error, 200) if body.error else None
        hub.live(device_id).acks.append({"id": body.id, "ok": body.ok, "error": error})
        return Response(status_code=204)

    @r.get("/web/state")
    def web_state(device_id: str = Depends(web_auth), hub: Hub = Depends(get_hub)) -> dict:
        live = hub.live(device_id)
        now = hub.clock()
        return {
            "phone_online": live.online(now),
            "state_age_s": round(now - live.state_at, 1) if live.state is not None else None,
            "state": live.state,
            "acks": list(live.acks),
        }

    @r.post("/web/command", status_code=202)
    def web_command(body: CommandIn, device_id: str = Depends(web_auth), hub: Hub = Depends(get_hub)) -> dict:
        model = COMMANDS.get(body.type)
        if model is None:
            raise HTTPException(422, "unknown command")
        try:
            args = model.model_validate(body.args).model_dump(exclude_none=True)
        except ValidationError as e:
            raise HTTPException(422, "bad args") from e
        live = hub.live(device_id)
        if not live.online(hub.clock()):
            raise HTTPException(409, "phone offline")
        cmd = {"id": secrets.token_hex(8), "type": body.type, "args": args}
        try:
            live.queue.put_nowait(cmd)
        except asyncio.QueueFull as e:
            raise HTTPException(503, "phone busy") from e
        return {"id": cmd["id"]}

    return r

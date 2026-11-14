"""FocusLens backend.

POST /hint    photo + audio -> micro-steps (quest) + context card
POST /check   photo of the user's work -> is the current micro-step done?
POST /dump    short voice note -> one line (brain-dump task or block goal)
POST /review  today's cards -> active-recall flashcards
POST /tts     text -> WAV (one fixed voice)
GET  /health
"""

import asyncio
import hmac
import json
import logging
import time
from collections.abc import Awaitable, Callable
from functools import lru_cache
from typing import Annotated, Literal, TypeVar

from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field, ValidationError, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

import remote
from ai import ASK_HINTS, MODE_HINTS, SITUATIONS, Check, Dump, Gemini, Hint, Review, clean, lang_code
from formula import render_png

log = logging.getLogger("focuslens")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

T = TypeVar("T")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    gemini_api_key: str
    app_token: str  # shared bearer token the phone app sends

    # Fast + cheap default; fallback is a different model so one model's outage/overload doesn't kill the demo.
    hint_model: str = "gemini-3.5-flash-lite"
    hint_thinking: str = "minimal"
    hint_fallback_model: str = "gemini-3.1-flash-lite"
    hint_fallback_thinking: str = "minimal"

    tts_model: str = "gemini-3.8-flash-lite-tts"
    tts_voice: str = "Kore"

    max_audio_mb: float = 10
    max_dump_mb: float = 2  # ~60 s of 16 kHz WAV; a brain dump is â‰¤ 20 s
    max_photo_mb: float = 5
    gemini_timeout_s: float = 10  # per attempt; a hung primary must fail over fast (user is waiting on the HUD)

    public_url: str = "https://lens.projectviridis.dev"  # base of the glasses web-app install URL


@lru_cache
def get_settings() -> Settings:
    return Settings()


@lru_cache
def get_ai() -> Gemini:
    s = get_settings()
    return Gemini(s.gemini_api_key, s.gemini_timeout_s)


def auth(authorization: Annotated[str, Header()] = "", s: Settings = Depends(get_settings)) -> None:
    token = authorization.removeprefix("Bearer ").strip()
    if not hmac.compare_digest(token.encode(), s.app_token.encode()):
        raise HTTPException(401, "bad token")


app = FastAPI(title="FocusLens backend")

# Glasses app menu: web app (static) + relay to the phone (remote.py).
def public_url(s: Settings = Depends(get_settings)) -> str:
    return s.public_url


app.include_router(remote.create_router(auth, public_url))
app.mount("/glasses", StaticFiles(directory=Path(__file__).parent / "glasses", html=True, check_dir=False),
          name="glasses")

_WEB_HEADERS = {
    "Content-Security-Policy": "default-src 'self'; connect-src 'self'; img-src 'self' data:; "
                               "style-src 'self'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",  # the install URL carries a one-time pairing code
    "Cache-Control": "no-cache",
}


class SecurityHeaders:
    """Pure ASGI (not BaseHTTPMiddleware) so the SSE stream and its disconnect detection stay untouched."""

    def __init__(self, inner):
        self.inner = inner

    async def __call__(self, scope, receive, send):
        path = scope.get("path", "") if scope["type"] == "http" else ""
        extra = _WEB_HEADERS if path.startswith("/glasses") else {"Cache-Control": "no-store"} if path.startswith("/remote") else None
        if extra is None:
            return await self.inner(scope, receive, send)
        names = {k.lower().encode() for k in extra}

        async def send_with_headers(message):
            if message["type"] == "http.response.start":
                headers = [(k, v) for k, v in message.get("headers", []) if k.lower() not in names]
                message["headers"] = headers + [(k.lower().encode(), v.encode()) for k, v in extra.items()]
            await send(message)

        await self.inner(scope, receive, send_with_headers)


app.add_middleware(SecurityHeaders)


@app.get("/health")
def health() -> dict:
    return {"ok": True}


async def _read(f: UploadFile | None, max_mb: float, name: str) -> bytes | None:
    if f is None:
        return None
    data = await f.read(int(max_mb * 1024 * 1024) + 1)
    if len(data) > max_mb * 1024 * 1024:
        raise HTTPException(413, f"{name} larger than {max_mb} MB")
    return data or None


def _audio_mime(f: UploadFile | None) -> str:
    mime = (f.content_type if f else None) or "audio/wav"
    return "audio/wav" if mime == "application/octet-stream" else mime


def _meta(model: type[T], raw: str) -> T:
    try:
        return model.model_validate_json(raw)
    except ValidationError as e:
        raise HTTPException(422, "bad meta") from e


async def _with_fallback(name: str, s: Settings, call: Callable[[str, str], Awaitable[tuple[T, dict]]],
                         sizes: str = "") -> T:
    """Primary model, then the fallback model. Logs metadata only (never photos, audio or text)."""
    attempts = [(s.hint_model, s.hint_thinking), (s.hint_fallback_model, s.hint_fallback_thinking)]
    t0 = time.perf_counter()
    for i, (model, thinking) in enumerate(attempts):
        try:
            result, usage = await call(model, thinking)
            log.info("%s ok %s ms=%d %s", name, json.dumps(usage), (time.perf_counter() - t0) * 1000, sizes)
            return result
        except Exception as e:  # noqa: BLE001 (any Gemini/parse failure -> try fallback)
            log.warning("%s failed model=%s attempt=%d err=%s: %s", name, model, i, type(e).__name__, e)
    raise HTTPException(502, "AI unavailable, try again")


async def _render(obj: Hint | Check) -> None:
    if obj.formula:
        obj.formula_png = await asyncio.to_thread(render_png, obj.formula)
        if obj.formula_png is None:
            obj.formula = ""  # mathtext could not lay it out; the text still stands


# ---------------------------------------------------------------- request metadata (all untrusted)

Capped = Annotated[str, Field(max_length=2000)]


def _clean_list(v: list[str], n: int) -> list[str]:
    return [c for c in (clean(x, n) for x in v) if c]


class RecentHint(BaseModel):
    """An earlier hint of the same focus block (sent back by the phone, so: untrusted)."""

    situation: str = "other"
    title: str = ""
    step: str = ""

    @field_validator("situation")
    @classmethod
    def _situation(cls, v: str) -> str:
        return v if v in SITUATIONS else "other"

    @field_validator("title", "step")
    @classmethod
    def _text(cls, v: str) -> str:
        return clean(v, 200)


class UserContext(BaseModel):
    locale: str = "cs"
    goal: str = Field(default="", max_length=1000)
    mistakes: list[str] = Field(default_factory=list, max_length=10)

    @field_validator("locale")
    @classmethod
    def _locale(cls, v: str) -> str:
        return lang_code(v)

    @field_validator("goal")
    @classmethod
    def _goal(cls, v: str) -> str:
        return clean(v, 200)

    @field_validator("mistakes")
    @classmethod
    def _mistakes(cls, v: list[str]) -> list[str]:
        return _clean_list(v, 120)


class HintMeta(UserContext):
    """Only whitelisted values may reach the prompt as instructions; the rest is quoted DATA."""

    mode: str = "auto"  # auto | lecture | homework | desk
    ask: str = "help"  # help | smaller | rescue
    ts: str | None = Field(default=None, max_length=40)
    previous: list[str] = Field(default_factory=list, max_length=5)  # for swipe right: "and what next?"
    recent: list[RecentHint] = Field(default_factory=list, max_length=6)  # earlier hints, oldest first

    @field_validator("mode")
    @classmethod
    def _mode(cls, v: str) -> str:
        return v if v in MODE_HINTS else "auto"

    @field_validator("ask")
    @classmethod
    def _ask(cls, v: str) -> str:
        return v if v in ASK_HINTS else "help"

    @field_validator("previous")
    @classmethod
    def _previous(cls, v: list[str]) -> list[str]:
        return _clean_list(v, 300)


class Quest(BaseModel):
    title: str = Field(default="", max_length=1000)
    steps: list[Capped] = Field(min_length=1, max_length=6)
    index: int = Field(ge=0)

    @field_validator("title")
    @classmethod
    def _title(cls, v: str) -> str:
        return clean(v, 120)

    @field_validator("steps")
    @classmethod
    def _steps(cls, v: list[str]) -> list[str]:
        out = _clean_list(v, 200)
        if not out:
            raise ValueError("empty steps")
        return out

    @model_validator(mode="after")
    def _index_in_range(self) -> "Quest":
        if self.index >= len(self.steps):
            raise ValueError("index out of range")
        return self


class CheckMeta(UserContext):
    quest: Quest


class DumpMeta(BaseModel):
    locale: str = "cs"
    kind: Literal["task", "goal"] = "task"


class ReviewItem(BaseModel):
    title: Capped = ""
    summary: Capped = ""
    correction: Capped = ""
    mistake: Capped = ""

    @field_validator("title", "mistake")
    @classmethod
    def _short(cls, v: str) -> str:
        return clean(v, 120)

    @field_validator("summary", "correction")
    @classmethod
    def _long(cls, v: str) -> str:
        return clean(v, 400)


class ReviewIn(BaseModel):
    locale: str = "cs"
    items: list[ReviewItem] = Field(min_length=1, max_length=20)

    @field_validator("locale")
    @classmethod
    def _locale(cls, v: str) -> str:
        return lang_code(v)


# ---------------------------------------------------------------- endpoints

Meta = Annotated[str, Form(max_length=8000)]


@app.post("/hint", response_model=Hint, dependencies=[Depends(auth)])
async def hint(
    photo: Annotated[UploadFile | None, File()] = None,
    audio: Annotated[UploadFile | None, File()] = None,
    meta: Meta = "{}",
    s: Settings = Depends(get_settings),
    ai: Gemini = Depends(get_ai),
) -> Hint:
    m = _meta(HintMeta, meta)
    photo_b = await _read(photo, s.max_photo_mb, "photo")
    audio_b = await _read(audio, s.max_audio_mb, "audio")
    if not photo_b and not audio_b:
        raise HTTPException(422, "send photo and/or audio")
    recent = [r.model_dump() for r in m.recent if r.step]
    result = await _with_fallback("hint", s, lambda model, thinking: ai.hint(
        model=model, thinking=thinking, photo=photo_b, audio=audio_b, audio_mime=_audio_mime(audio),
        locale=m.locale, mode=m.mode, previous=m.previous, recent=recent,
        goal=m.goal, mistakes=m.mistakes, ask=m.ask,
    ), sizes=f"photo={len(photo_b or b'')} audio={len(audio_b or b'')}")
    await _render(result)
    return result


@app.post("/check", response_model=Check, dependencies=[Depends(auth)])
async def check(
    photo: Annotated[UploadFile | None, File()] = None,
    meta: Meta = "{}",
    s: Settings = Depends(get_settings),
    ai: Gemini = Depends(get_ai),
) -> Check:
    m = _meta(CheckMeta, meta)
    photo_b = await _read(photo, s.max_photo_mb, "photo")
    if not photo_b:
        raise HTTPException(422, "send a photo")
    quest = m.quest.model_dump()
    result = await _with_fallback("check", s, lambda model, thinking: ai.check(
        model=model, thinking=thinking, photo=photo_b, locale=m.locale, goal=m.goal,
        mistakes=m.mistakes, quest=quest,
    ), sizes=f"photo={len(photo_b)}")
    await _render(result)
    return result


@app.post("/dump", response_model=Dump, dependencies=[Depends(auth)])
async def dump(
    audio: Annotated[UploadFile | None, File()] = None,
    meta: Meta = "{}",
    s: Settings = Depends(get_settings),
    ai: Gemini = Depends(get_ai),
) -> Dump:
    m = _meta(DumpMeta, meta)
    audio_b = await _read(audio, s.max_dump_mb, "audio")
    if not audio_b:
        raise HTTPException(422, "send audio")
    return await _with_fallback("dump", s, lambda model, thinking: ai.dump(
        model=model, thinking=thinking, audio=audio_b, audio_mime=_audio_mime(audio), kind=m.kind,
    ), sizes=f"audio={len(audio_b)} kind={m.kind}")


@app.post("/review", response_model=Review, dependencies=[Depends(auth)])
async def review(body: ReviewIn, s: Settings = Depends(get_settings), ai: Gemini = Depends(get_ai)) -> Review:
    items = [i.model_dump() for i in body.items]
    return await _with_fallback("review", s, lambda model, thinking: ai.review(
        model=model, thinking=thinking, locale=body.locale, items=items,
    ), sizes=f"items={len(items)}")


class TtsIn(BaseModel):
    text: str = Field(min_length=1, max_length=500)
    voice: str | None = None  # ignored: one fixed voice (TTS_VOICE) for every answer


@app.post("/tts", dependencies=[Depends(auth)], response_class=Response,
          responses={200: {"content": {"audio/wav": {}}}})
async def tts(body: TtsIn, s: Settings = Depends(get_settings), ai: Gemini = Depends(get_ai)) -> Response:
    text = clean(body.text, 500)
    if not text:
        raise HTTPException(422, "empty text")
    t0 = time.perf_counter()
    try:
        wav = await ai.tts(model=s.tts_model, voice=s.tts_voice, text=text)
    except Exception as e:  # noqa: BLE001
        log.warning("tts failed err=%s: %s", type(e).__name__, e)
        raise HTTPException(502, "TTS unavailable") from e
    log.info("tts ok ms=%d bytes=%d", (time.perf_counter() - t0) * 1000, len(wav))
    return Response(wav, media_type="audio/wav")

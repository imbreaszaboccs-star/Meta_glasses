import json

import pytest
from fastapi.testclient import TestClient

import app as backend
import remote

TOKEN = "test-token"


class Clock:
    def __init__(self):
        self.t = 1_000_000.0

    def __call__(self):
        return self.t


@pytest.fixture
def env(monkeypatch):
    monkeypatch.setattr(remote, "SSE_MAX_S", 1.0)
    monkeypatch.setattr(remote, "SSE_PING_S", 0.2)
    clock = Clock()
    store, hub = remote.Store(":memory:"), remote.Hub(clock)
    backend.app.dependency_overrides[backend.get_settings] = lambda: backend.Settings(
        gemini_api_key="x", app_token=TOKEN, public_url="https://lens.example", _env_file=None)
    backend.app.dependency_overrides[remote.get_store] = lambda: store
    backend.app.dependency_overrides[remote.get_hub] = lambda: hub
    yield TestClient(backend.app), clock
    backend.app.dependency_overrides.clear()


def pair(c, body=None):
    r = c.post("/remote/pair", headers={"Authorization": f"Bearer {TOKEN}"}, json=body or {})
    assert r.status_code == 200, r.text
    return r.json()


def phone_h(p):
    return {"X-Device-Id": p["device_id"], "Authorization": f"Bearer {p['phone_secret']}"}


def web_h(token):
    return {"Authorization": f"Bearer {token}"}


def claim(c, code):
    return c.post("/remote/claim", json={"code": code})


def paired_web(c):
    p = pair(c)
    token = claim(c, p["code"]).json()["web_token"]
    return p, token


def test_pair_requires_app_token(env):
    c, _ = env
    assert c.post("/remote/pair", json={}).status_code == 401


def test_pair_returns_install_url_and_reuses_device(env):
    c, _ = env
    p = pair(c)
    assert len(p["code"]) == 8 and p["expires_in"] == 600
    assert p["web_url"] == f"https://lens.example/glasses/?code={p['code']}"
    assert p["install_url"].startswith("fb-viewapp://web_app_deep_link?appName=FocusLens&appUrl=https%3A%2F%2Flens.example")
    again = pair(c, {"device_id": p["device_id"], "phone_secret": p["phone_secret"]})
    assert again["device_id"] == p["device_id"] and again["code"] != p["code"]
    assert claim(c, p["code"]).status_code == 404  # a new code replaces the old one
    forged = pair(c, {"device_id": p["device_id"], "phone_secret": "wrong"})
    assert forged["device_id"] != p["device_id"]  # wrong secret never hijacks a device


def test_claim_is_single_use_and_expires(env):
    c, clock = env
    p = pair(c)
    r = claim(c, p["code"].lower())  # case-insensitive
    assert r.status_code == 200 and len(r.json()["web_token"]) > 30
    assert claim(c, p["code"]).status_code == 404
    p2 = pair(c, {"device_id": p["device_id"], "phone_secret": p["phone_secret"]})
    clock.t += 601
    assert claim(c, p2["code"]).status_code == 404


def test_claim_rate_limited(env):
    c, _ = env
    codes = [claim(c, "AAAAAAAA").status_code for _ in range(11)]
    assert codes[:10] == [404] * 10 and codes[10] == 429


def test_web_and_phone_auth(env):
    c, _ = env
    assert c.get("/remote/web/state").status_code == 401
    assert c.get("/remote/web/state", headers=web_h("nope")).status_code == 401
    assert c.post("/remote/phone/state", headers={"X-Device-Id": "x", "Authorization": "Bearer y"}, json={}).status_code == 401


def test_state_roundtrip_and_online(env):
    c, clock = env
    p, token = paired_web(c)
    s = c.get("/remote/web/state", headers=web_h(token)).json()
    assert s == {"phone_online": False, "state_age_s": None, "state": None, "acks": []}
    state = {"v": 1, "focus": {"running": True, "goal": "Stats"}}
    assert c.post("/remote/phone/state", headers=phone_h(p), json=state).status_code == 204
    s = c.get("/remote/web/state", headers=web_h(token)).json()
    assert s["phone_online"] and s["state"] == state and s["state_age_s"] == 0
    clock.t += 61
    assert c.get("/remote/web/state", headers=web_h(token)).json()["phone_online"] is False


@pytest.mark.parametrize("body,code", [
    (b"x" * (64 * 1024 + 1), 413), (b"{not json", 422), (b"[1,2]", 422),
])
def test_state_validation(env, body, code):
    c, _ = env
    p = pair(c)
    assert c.post("/remote/phone/state", headers=phone_h(p), content=body).status_code == code


def test_command_needs_online_phone(env):
    c, _ = env
    _, token = paired_web(c)
    r = c.post("/remote/web/command", headers=web_h(token), json={"type": "help"})
    assert r.status_code == 409


@pytest.mark.parametrize("cmd", [
    {"type": "rm -rf"}, {"type": "start_focus", "args": {"mode": "pirate"}},
    {"type": "start_focus", "args": {"evil": 1}}, {"type": "inbox_done", "args": {"id": "../x", "done": True}},
    {"type": "inbox_done", "args": {"id": "abc"}}, {"type": "set_config", "args": {"nudges": "yes please"}},
])
def test_command_whitelist(env, cmd):
    c, _ = env
    p, token = paired_web(c)
    c.post("/remote/phone/state", headers=phone_h(p), json={})
    assert c.post("/remote/web/command", headers=web_h(token), json=cmd).status_code == 422


def test_command_delivered_over_sse_and_acked(env):
    c, _ = env
    p, token = paired_web(c)
    c.post("/remote/phone/state", headers=phone_h(p), json={})
    r = c.post("/remote/web/command", headers=web_h(token),
               json={"type": "start_focus", "args": {"mode": "lecture", "goal": "Entropy‮ https://x.io"}})
    assert r.status_code == 202
    cmd_id = r.json()["id"]
    got = None
    with c.stream("GET", "/remote/phone/events", headers=phone_h(p)) as s:
        assert s.headers["content-type"].startswith("text/event-stream")
        for line in s.iter_lines():
            if line.startswith("data: "):
                got = json.loads(line[6:])
                break
    assert got == {"id": cmd_id, "type": "start_focus", "args": {"mode": "lecture", "goal": "Entropy"}}
    assert c.post("/remote/phone/ack", headers=phone_h(p), json={"id": cmd_id, "ok": True}).status_code == 204
    acks = c.get("/remote/web/state", headers=web_h(token)).json()["acks"]
    assert acks == [{"id": cmd_id, "ok": True, "error": None}]


def test_unpair_revokes_everything(env):
    c, _ = env
    p, token = paired_web(c)
    c.post("/remote/phone/state", headers=phone_h(p), json={"secret": "notes"})
    assert c.post("/remote/unpair", headers=phone_h(p)).status_code == 204
    assert c.get("/remote/web/state", headers=web_h(token)).status_code == 401
    assert c.post("/remote/phone/state", headers=phone_h(p), json={}).status_code == 401


def test_glasses_static_has_security_headers(env):
    c, _ = env
    r = c.get("/glasses/")
    assert r.status_code == 200 and "text/html" in r.headers["content-type"]
    assert "default-src 'self'" in r.headers["content-security-policy"]
    assert r.headers["referrer-policy"] == "no-referrer"
    assert c.get("/remote/web/state").headers["cache-control"] == "no-store"

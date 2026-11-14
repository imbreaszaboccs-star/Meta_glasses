import json

import pytest
from fastapi.testclient import TestClient

import app as backend
from ai import Card, Hint, pcm_to_wav

TOKEN = "test-token"


class FakeAI:
    def __init__(self, fail_models=()):
        self.fail_models = set(fail_models)
        self.calls = []

    async def hint(self, **kw):
        self.calls.append(kw)
        if kw["model"] in self.fail_models:
            raise RuntimeError("boom")
        return Hint(next_step="Fix the minus on line 3.", card=Card(title="t", summary="s")), {"model": kw["model"]}

    async def tts(self, **kw):
        return pcm_to_wav(b"\x00\x00" * 10)


@pytest.fixture
def make_client():
    def _make(ai=None):
        ai = ai or FakeAI()
        backend.app.dependency_overrides[backend.get_settings] = lambda: backend.Settings(
            gemini_api_key="x", app_token=TOKEN, max_photo_mb=0.001, _env_file=None
        )
        backend.app.dependency_overrides[backend.get_ai] = lambda: ai
        return TestClient(backend.app), ai

    yield _make
    backend.app.dependency_overrides.clear()


H = {"Authorization": f"Bearer {TOKEN}"}


def test_health(make_client):
    c, _ = make_client()
    assert c.get("/health").json() == {"ok": True}


def test_hint_requires_token(make_client):
    c, _ = make_client()
    assert c.post("/hint", files={"audio": ("a.wav", b"RIFF", "audio/wav")}).status_code == 401
    assert c.post("/hint", headers={"Authorization": "Bearer nope"},
                  files={"audio": ("a.wav", b"RIFF", "audio/wav")}).status_code == 401


def test_hint_ok_passes_meta(make_client):
    c, ai = make_client()
    meta = {"locale": "en", "mode": "homework", "previous": ["Step one."]}
    r = c.post("/hint", headers=H, data={"meta": json.dumps(meta)},
               files={"photo": ("p.jpg", b"\xff\xd8jpg", "image/jpeg"), "audio": ("a.wav", b"RIFFwav", "audio/wav")})
    assert r.status_code == 200, r.text
    assert r.json()["next_step"] == "Fix the minus on line 3."
    call = ai.calls[0]
    assert call["locale"] == "en" and call["mode"] == "homework" and call["previous"] == ["Step one."]
    assert call["photo"] == b"\xff\xd8jpg" and call["audio_mime"] == "audio/wav"


def test_hint_audio_only(make_client):
    c, ai = make_client()
    r = c.post("/hint", headers=H, files={"audio": ("a.wav", b"RIFFwav", "application/octet-stream")})
    assert r.status_code == 200
    assert ai.calls[0]["photo"] is None and ai.calls[0]["audio_mime"] == "audio/wav"


def test_hint_needs_some_input(make_client):
    c, _ = make_client()
    assert c.post("/hint", headers=H, data={"meta": "{}"}).status_code == 422


def test_hint_rejects_big_photo(make_client):
    c, _ = make_client()  # max_photo_mb ~ 1 kB in tests
    r = c.post("/hint", headers=H, files={"photo": ("p.jpg", b"x" * 5000, "image/jpeg")})
    assert r.status_code == 413


def test_hint_falls_back_to_second_model(make_client):
    c, ai = make_client(FakeAI(fail_models={"gemini-3.5-flash-lite"}))
    r = c.post("/hint", headers=H, files={"audio": ("a.wav", b"RIFF", "audio/wav")})
    assert r.status_code == 200
    assert [x["model"] for x in ai.calls] == ["gemini-3.5-flash-lite", "gemini-3.1-flash-lite"]


def test_hint_502_when_all_models_fail(make_client):
    c, _ = make_client(FakeAI(fail_models={"gemini-3.5-flash-lite", "gemini-3.1-flash-lite"}))
    r = c.post("/hint", headers=H, files={"audio": ("a.wav", b"RIFF", "audio/wav")})
    assert r.status_code == 502


def test_tts_returns_wav(make_client):
    c, _ = make_client()
    r = c.post("/tts", headers=H, json={"text": "Ahoj"})
    assert r.status_code == 200 and r.headers["content-type"] == "audio/wav"
    assert r.content[:4] == b"RIFF"

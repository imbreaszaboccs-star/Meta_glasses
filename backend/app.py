"""FocusLens backend: POST /hint (photo + audio -> one next step), POST /tts, GET /health."""

import hmac
import json
import logging
import time
from functools import lru_cache
from typing import Annotated

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from ai import Gemini, Hint

log = logging.getLogger("focuslens")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


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
    max_photo_mb: float = 5
    gemini_timeout_s: float = 10  # per attempt; a hung primary must fail over fast (user is waiting on the HUD)


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


class HintMeta(BaseModel):
    locale: str = "cs"
    mode: str = "lecture"  # lecture | homework | desk
    ts: str | None = None
    previous: list[str] = Field(default_factory=list, max_length=5)  # for swipe right: "and what next?"


@app.post("/hint", response_model=Hint, dependencies=[Depends(auth)])
async def hint(
    photo: Annotated[UploadFile | None, File()] = None,
    audio: Annotated[UploadFile | None, File()] = None,
    meta: Annotated[str, Form()] = "{}",
    s: Settings = Depends(get_settings),
    ai: Gemini = Depends(get_ai),
) -> Hint:
    m = HintMeta.model_validate_json(meta)
    photo_b = await _read(photo, s.max_photo_mb, "photo")
    audio_b = await _read(audio, s.max_audio_mb, "audio")
    if not photo_b and not audio_b:
        raise HTTPException(422, "send photo and/or audio")
    audio_mime = (audio.content_type if audio else None) or "audio/wav"
    if audio_mime == "application/octet-stream":
        audio_mime = "audio/wav"

    attempts = [(s.hint_model, s.hint_thinking), (s.hint_fallback_model, s.hint_fallback_thinking)]
    t0 = time.perf_counter()
    for i, (model, thinking) in enumerate(attempts):
        try:
            result, usage = await ai.hint(
                model=model, thinking=thinking, photo=photo_b, audio=audio_b, audio_mime=audio_mime,
                locale=m.locale, mode=m.mode, previous=m.previous,
            )
            # Metadata only. No photos, audio or transcripts in logs.
            log.info("hint ok %s ms=%d photo=%s audio=%s",
                     json.dumps(usage), (time.perf_counter() - t0) * 1000,
                     len(photo_b or b""), len(audio_b or b""))
            return result
        except Exception as e:  # noqa: BLE001 (any Gemini/parse failure -> try fallback)
            log.warning("hint failed model=%s attempt=%d err=%s: %s", model, i, type(e).__name__, e)
    raise HTTPException(502, "AI unavailable, try again")


class TtsIn(BaseModel):
    text: str = Field(min_length=1, max_length=500)
    voice: str | None = None


@app.post("/tts", dependencies=[Depends(auth)], response_class=Response,
          responses={200: {"content": {"audio/wav": {}}}})
async def tts(body: TtsIn, s: Settings = Depends(get_settings), ai: Gemini = Depends(get_ai)) -> Response:
    t0 = time.perf_counter()
    try:
        wav = await ai.tts(model=s.tts_model, voice=body.voice or s.tts_voice, text=body.text)
    except Exception as e:  # noqa: BLE001
        log.warning("tts failed err=%s: %s", type(e).__name__, e)
        raise HTTPException(502, "TTS unavailable") from e
    log.info("tts ok ms=%d bytes=%d", (time.perf_counter() - t0) * 1000, len(wav))
    return Response(wav, media_type="audio/wav")

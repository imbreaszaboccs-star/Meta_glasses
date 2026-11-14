"""Gemini calls: one multimodal call for /hint, one TTS call for /tts."""

import io
import json
import wave

from google import genai
from google.genai import types
from pydantic import BaseModel

SYSTEM_PROMPT = """\
You are an executive-function copilot for a person with ADHD, speaking through smart glasses.
You get a photo of exactly what they are looking at (board, notebook, desk...) and audio of the
last ~3 minutes around them. The LAST ~60 SECONDS of audio matter most: that is where they zoned out.

First fill `check`: re-verify every written line/calculation in the photo yourself (recompute the
arithmetic and signs, do not trust what is written), and note what the speaker stressed.
A mistake in their work ALWAYS beats "continue with the next step".

Then return ONE concrete next step:
- max 20 words, imperative, no greeting, no preamble, no lists
- address the user informally (Czech/Slovak: tykání, e.g. "Oprav", never "Opravte")
- do NOT give away the whole solution, only the nearest step
- if there is a mistake in the photo, say exactly where (line, symbol)
- if they lost the thread of a lecture, say what was just said / what to write down
- if the photo and audio are unclear, give the safest small step (e.g. "Write down the formula on the left of the board.")

Also fill a context card the user reviews in the evening (this may be longer, but stay concise).
Write next_step and the whole card in the language named in the request (transcript stays verbatim)."""

MODE_HINTS = {
    "lecture": "They are in a lecture. Focus on what the speaker just explained.",
    "homework": "They are solving a problem on paper. Find the FIRST mistake; only if there is none, give the next step.",
    "desk": "They are stuck starting a task at their desk. Pick the smallest physical first action.",
}

LANGUAGES = {"cs": "Czech", "sk": "Slovak", "en": "English", "de": "German", "pl": "Polish"}

# Hand-written (no $ref) so Gemini's structured output accepts it as-is.
HINT_SCHEMA = {
    "type": "object",
    "properties": {
        # First on purpose: the model "thinks" here before writing next_step. Not returned to the app.
        "check": {"type": "string", "description": "Max 40 words, English: line-by-line verification + what the speaker stressed."},
        "next_step": {"type": "string", "description": "One step, max 20 words."},
        "card": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Max 8 words."},
                "summary": {"type": "string", "description": "2-4 sentences: situation and what happened."},
                "correction": {"type": "string", "description": "The mistake and its fix, or empty string."},
                "transcript_tail": {"type": "string", "description": "Verbatim transcript of the last ~60 s of speech, empty if none."},
            },
            "required": ["title", "summary", "correction", "transcript_tail"],
        },
    },
    "required": ["check", "next_step", "card"],
}


class Card(BaseModel):
    title: str
    summary: str
    correction: str = ""
    transcript_tail: str = ""


class Hint(BaseModel):
    next_step: str
    card: Card


class Gemini:
    def __init__(self, api_key: str, timeout_s: float = 30):
        # v1beta: SDK 3.3 defaults to v1, which rejects speech_config (TTS).
        self.client = genai.Client(
            api_key=api_key,
            http_options=types.HttpOptions(api_version="v1beta", timeout=int(timeout_s * 1000)),
        )

    async def hint(
        self,
        *,
        model: str,
        thinking: str,
        photo: bytes | None,
        audio: bytes | None,
        audio_mime: str,
        locale: str,
        mode: str,
        previous: list[str],
    ) -> tuple[Hint, dict]:
        parts: list = []
        if photo:
            parts.append(types.Part.from_bytes(data=photo, mime_type="image/jpeg"))
        if audio:
            parts.append(types.Part.from_bytes(data=audio, mime_type=audio_mime))

        lang = LANGUAGES.get(locale.split("-")[0].lower(), locale)
        task = [f"Answer language: {lang}.", MODE_HINTS.get(mode, "")]
        if not photo:
            task.append("No photo this time (camera unavailable). Use the audio only.")
        if not audio:
            task.append("No audio this time. Use the photo only.")
        if previous:
            task.append(
                "The user already got these steps (most recent last) and wants the NEXT one, "
                "do not repeat them:\n- " + "\n- ".join(previous)
            )
        parts.append("\n".join(t for t in task if t))

        resp = await self.client.aio.models.generate_content(
            model=model,
            contents=parts,
            config=types.GenerateContentConfig(
                system_instruction=SYSTEM_PROMPT,
                response_mime_type="application/json",
                response_json_schema=HINT_SCHEMA,
                thinking_config=types.ThinkingConfig(thinking_level=thinking),
                max_output_tokens=2048,
            ),
        )
        if not resp.text:
            reason = resp.candidates[0].finish_reason if resp.candidates else resp.prompt_feedback
            raise RuntimeError(f"empty response from {model}: {reason}")
        usage = resp.usage_metadata
        meta = {
            "model": model,
            "in_tokens": usage.prompt_token_count if usage else None,
            "out_tokens": usage.candidates_token_count if usage else None,
        }
        return Hint.model_validate(json.loads(resp.text)), meta

    async def tts(self, *, model: str, voice: str, text: str) -> bytes:
        """Returns a WAV file (24 kHz mono 16-bit)."""
        resp = await self.client.aio.models.generate_content(
            model=model,
            contents=text,
            config=types.GenerateContentConfig(
                response_modalities=["AUDIO"],
                speech_config=types.SpeechConfig(
                    voice_config=types.VoiceConfig(
                        prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name=voice)
                    )
                ),
            ),
        )
        try:
            data = resp.candidates[0].content.parts[0].inline_data.data
        except (AttributeError, IndexError, TypeError) as e:
            raise RuntimeError(f"no audio from {model}") from e
        return data if data[:4] == b"RIFF" else pcm_to_wav(data)


def pcm_to_wav(pcm: bytes, rate: int = 24000) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return buf.getvalue()

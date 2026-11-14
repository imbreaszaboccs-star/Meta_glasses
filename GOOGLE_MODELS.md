# Google models for FocusLens (research, 2026-11-14)

Sources: AI Studio model list and pricing (`aistudio.google.com/docs/models`, `/docs/pricing`;
the old `ai.google.dev/gemini-api/docs/*` URLs redirect there), thinking/audio/TTS docs, `google-genai` 3.3.0 on PyPI.
Prices are USD per 1M tokens, standard paid tier. Re-check before production, because they change often (see the price increases below).

## What FocusLens needs from a model




| Use | Input → output | Hard requirement |
|---|---|---|
| **Hint** (core, on every pinch) | JPEG + 180 s audio → 1 sentence + card JSON | **latency** (< 4 s end-to-end goal), native audio, vision, structured output, Czech |
| Card transcript | the same audio → last ~60 s verbatim | can come from the same call |
| Spoken output (glasses without display) | text → speech | Czech, natural voice, cheap |
| (later) Search over cards | text/photo → embeddings | multimodal |

**Key finding:** Gemini models take **audio natively** (32 tokens/s, so 180 s = 5 760 tokens), so the hint is
**one call** (photo + audio → JSON). The old plan's separate STT step disappears, which saves a network hop and one vendor.

## Text/multimodal models (candidates for /hint)

| Model ID | Status | Input $ (audio) | Output $ | Thinking | Notes |
|---|---|---|---|---|---|
| **`gemini-3.5-flash-lite`** | stable | 0.30 (same for audio) | 2.50 | default `minimal` ("near-off, lowest latency") | fastest/cheapest current gen. **← default** |
| `gemini-3.1-flash-lite` | stable | 0.25 (audio 0.50) | 1.50 | supports `minimal` | different model = good **fallback** |
| `gemini-3.6-flash` | stable, prev-gen | 0.75 ⚠️ | 3.75 ⚠️ | supports `minimal`, default `medium` | middle option |
| `gemini-3.8-flash` | stable, newest Flash | 0.75 ⚠️ | 3.75 ⚠️ | `low`+ only (no `minimal`), default `medium` | smartest Flash, noticeably slower (Roboflow vision evals: ~11.6 s vs ~2.7 s/sample for 3.5 Flash-Lite) |
| `gemini-4-hydrogen` | new | 1.50 | 7.50 | default `high`, no `minimal` | too slow/expensive for a pinch |
| `gemini-4-argon` / `gemini-4-carbon` | new, no free tier | 2.00 ⚠️⚠️ | 10.00 ⚠️⚠️ | | frontier reasoning, wrong tool here |
| `gemini-3.1-pro-preview` | preview | 2.00 | 12.00 | | preview + slow |
| `gemini-2.5-flash` / `-pro` | **deprecated** | | | | auto-routed to 3.5 Flash-Lite / 3.8 Flash on **2026-12-01**. Don't use |
| `gemini-2.5-flash-lite` | kept "until further notice" | 0.10 (audio 0.30) | 0.40 | off by default | cheapest, older, weaker vision |

⚠️ 3.6/3.8 Flash prices **double on 2027-01-01**; ⚠️⚠️ Gemini 4 Argon/Carbon prices **double on 2026-12-01**.

**Choice: `gemini-3.5-flash-lite` with `thinking_level=minimal`.** The task is easy for a frontier model
(read a photo, listen, say one step). Latency is the product, and it's the cheapest per hint.
Rough cost per hint (≈5.8k audio + ~1k image + ~0.4k prompt tokens in, ~300 out):
**≈ $0.003, so about 330 hints per $1**. The same with 3.8 Flash: ≈ $0.0065 now and ≈ $0.013 after Jan 2027.
If hint quality on real photos turns out weak, set `HINT_MODEL=gemini-3.6-flash` in `.env` (≈ +0.5 s, see below).
No code change needed. Avoid 3.8 Flash for now: it was overloaded during testing.

### Measured (2026-11-14, from a home PC in CZ, real key)
Test: handwritten quadratic equation with a planted sign error on line 3 (`25 + 24 = 49`, should be `25 − 24 = 1`)
plus Czech "lecture" audio saying "watch the sign" (generated with our `/tts`). Gemini call time only.

| Config | Median (20 s audio) | Caught error | Czech + informal |
|---|---|---|---|
| **3.5 Flash-Lite / minimal** | **2.6 s** (n=8) | 8/8 | 8/8 |
| 3.5 Flash-Lite / low | 2.4 s, one 6.2 s outlier (n=8) | 8/8 | 8/8 |
| 3.1 Flash-Lite / minimal (fallback) | 2.3 s (n=3) | 3/3 | 3/3 (formal "Opravte" before the tykání rule was added) |
| 3.6 Flash / minimal | 3.2 s (n=3) | 3/3 | 3/3 |
| 3.6 Flash / low | 4.2 s (n=3) | 3/3 | 3/3 |
| 3.8 Flash / low | **503 "high demand" ×2, 504 timeout ×1** | – | – |

- **Real payload (180 s, 16 kHz WAV = 5.76 MB, 5 908 input tokens):** 2.3–7.6 s, median ≈ 4 s across 9 runs. Backend overhead is ~50 ms.
  The spread comes from Gemini plus uploading 5.8 MB. **Compressing on the phone (Opus ~24 kbps, about 0.5 MB, accepted as `audio/ogg`)
  is the most important latency fix.** Token count stays the same (tokens are per second, not per byte); only the bytes shrink.
- **Prompt changes were what made it work, not a bigger model.** The first version missed the error and answered in English.
  Fixes: (1) the language is named ("Czech"), not just `cs`, and (2) a `check` field is first in the JSON schema, so the model
  re-verifies each line before writing `next_step`. That is cheap reasoning without paying for thinking. The app never sees it.
- `/tts` (3.8 Flash-Lite TTS): 1.5 s for one hint sentence.

## Speech output (/tts)

| Model ID | Status | In $ | Out $ (audio) | Notes |
|---|---|---|---|---|
| **`gemini-3.8-flash-lite-tts`** | new | 0.50 | 6.00 ⚠️ | fast, cheap, 101 languages incl. Czech. **← default** |
| `gemini-3.8-flash-tts` | new, flagship | 0.50 | 9.00 ⚠️ | more expressive, 130 languages |
| `gemini-3.1-flash-tts-preview`, `gemini-2.5-*-preview-tts` | legacy/preview | | | don't start on these |

30 prebuilt voices (Kore, Puck, Charon, Aoede…). Output is 24 kHz mono 16-bit. A 20-word hint ≈ 3–4 s of audio, a fraction of a cent.
On-phone `flutter_tts` stays as the free/offline fallback.

## Other relevant models (not used in the MVP)

| Model ID | What | Why not now / when |
|---|---|---|
| `gemini-3.5-transcribe` ($2 audio in / $12 text out) | dedicated STT with diarization + word timestamps | **7× the audio price** of 3.5 Flash-Lite and an extra call. Use it if cards need exact timestamps/speakers |
| `gemini-3.5-transcribe-live` | streaming STT | **Upgrade path for §3.4 of the architecture**: stream the mic continuously, keep *text* in the ring buffer instead of 5.5 MB WAV, so the upload goes to ~0 |
| `gemini-3.8-live` (+ `-extended-thinking`) | audio↔audio Live API ($3 audio in, $12 audio out) | always-on voice session = battery + cost; our design is tap-triggered |
| `gemini-embedding-2` | multimodal embeddings (text/image/audio) | evening search across cards ("where was the entropy formula?") |
| `gemini-decision-1` ($0.10 in, no output tokens) | yes/no / choice / score | cheap classifiers, e.g. auto-detect `mode` (lecture/homework/desk) from the photo |
| Nano Banana (`gemini-3.1-flash-lite-image` …) | image generation/editing | e.g. annotate the photo with a red circle around the mistake on the card |
| **Gemma 4** (DeepMind, open weights, Apache 2.0; E2B/E4B for phones, 12B for laptops) | on-device text/vision/audio (E2B/E4B audio ≤ 30 s) | offline mode / privacy mode on the phone. Too weak & too short audio window to replace the server call now |

## SDK notes (verified locally with google-genai 3.3.0)
- `client.models.generate_content` with `types.ThinkingConfig(thinking_level="minimal")`, `response_json_schema=…`.
- SDK 3.3.0 defaults to API `v1`, which **rejects `speech_config`**. The backend pins `api_version="v1beta"`.
- Docs now show a newer `client.interactions.create` API. We use `generate_content` because its config is typed and was verified against the live endpoint.

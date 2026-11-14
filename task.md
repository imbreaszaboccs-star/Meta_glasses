# FocusLens: tasks to finish the app

Goal: take the app from the *Gesture Lab* spike to a real Android app that does everything in [idea.md](idea.md), on **Meta Ray-Ban Display + Neural Band**, against the backend already running on the VPS.

Read first: [ARCHITECTURE.md](ARCHITECTURE.md) (what was verified on hardware), [backend/README.md](backend/README.md) (API contract), [GOOGLE_MODELS.md](GOOGLE_MODELS.md) (latency numbers).

---

## 0. Where we are (2026-11-14)

| Part | State | Notes |
|---|---|---|
| Backend `/hint`, `/tts`, `/health` | ✅ done, deployed | `https://lens.projectviridis.dev`, Bearer token, fallback model, stateless, unit tests + `smoke.py` |
| Meta DAT bridge (Kotlin) | ✅ spike, verified on device | [GlassesBridge.kt](focuslens/android/app/src/main/kotlin/com/focuslens/focuslens/GlassesBridge.kt): register, session on `SpecificDeviceSelector`, camera → STREAMING → display, `capturePhoto` 1.1–2.0 s, pinch = `Select`, swipe = `Nav`, Drag filtered, HUD text, debounce |
| Flutter UI | ⚠️ debug only | [main.dart](focuslens/lib/main.dart) is a log viewer with buttons |
| Audio ring buffer | ❌ | only designed (Dart sketch in ARCHITECTURE §3.3) |
| Photo + audio → backend → HUD | ❌ | the photo is saved locally and nothing is sent |
| Swipe next / previous, Back | ❌ | events are logged and nothing acts on them |
| Context cards, review screen | ❌ | |
| Background operation | ❌ | **the bridge lives in `MainActivity`**, so a locked phone or a closed app ends the focus session |
| Onboarding, settings, privacy controls | ❌ | |
| TTS output for glasses without a display | ❌ | backend `/tts` exists |
| iOS | ❌ | out of scope for this milestone (see §9) |

---

## 1. Key decision: the hot path runs natively in an Android foreground service

**Decision.** Everything between "pinch" and "hint on the HUD" runs in Kotlin inside a foreground service, `FocusService`. That covers the DAT session, inputs, camera, HUD, mic ring buffer, Opus encoding, upload, and writing the card. Flutter is the UI on top: onboarding, the Focus screen, cards, settings and diagnostics. It talks to the service over a MethodChannel and EventChannel.

**Why (this changes ARCHITECTURE.md §1, §3.3 and §9):**
- A focus block lasts 90 minutes with the phone locked in a pocket. The Flutter engine attached to an Activity is not guaranteed to live that long. A foreground service of type `microphone|connectedDevice` is.
- All DAT objects are already in Kotlin. Moving the ring buffer and upload next to them means a pinch never has to cross into Dart.
- Opus encoding is built into Android (`MediaCodec` + `MediaMuxer` OGG, API 29+, and our minSdk is 31). Dart has no good encoder. Opus cuts the upload from 5.8 MB to about 0.5 MB, which [GOOGLE_MODELS.md](GOOGLE_MODELS.md) names as the main latency fix.
- Cards are plain files written by the service. The UI reads them whenever it is open, so the two sides never have to be alive at the same time.

```
Neural Band ─► Meta RB Display ◄──BT──► ┌──────────── Android phone ────────────────────────────┐
                                        │ FocusService (foreground, Kotlin)                      │
                                        │  GlassesSession  (DAT session, inputs, camera, HUD)    │
                                        │  MicRing         (AudioRecord 16 kHz → 180 s RAM ring) │──HTTPS──► VPS /hint, /tts
                                        │  HintRunner      (snapshot+photo → Opus → /hint → HUD) │
                                        │  CardWriter      (filesDir/cards/<id>/…)               │
                                        │  RetryWorker     (WorkManager, pending cards)          │
                                        ├────────── MethodChannel / EventChannel ────────────────┤
                                        │ Flutter UI: Onboarding · Focus · Cards · Settings · Diag│
                                        └────────────────────────────────────────────────────────┘
```

---

## 2. Product rules (from idea.md, apply to every task)

1. **The phone stays in the pocket.** Every core action works from the Neural Band. The phone UI is for setup before and review after.
2. **One step, never a paragraph.** HUD text is at most 20 words. The longer text goes to the card.
3. **Always give feedback, never stay silent.** Every pinch gets an immediate HUD reaction, including when something fails.
4. **Privacy of the people around.** Audio stays in RAM until a pinch. There is a visible "listening" state, pause takes one tap, and old cards are deleted automatically.
5. **No guilt mechanics.** No streaks, no scores, no "you missed X", no red badge counters.
6. **Under 4 s** from pinch to hint (p50), measured and shown in diagnostics.

---

## 3. Phase 1: native hot path (`FocusService`)

Package `com.focuslens.focuslens.focus`. Split the current `GlassesBridge.kt` into the classes below. Keep the behaviour that was verified on hardware: the camera-before-display order, `SpecificDeviceSelector`, Drag filtering, and the handling of `DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED`.

### T1.1 `FocusService`, a foreground service that owns the session
- `foregroundServiceType="microphone|connectedDevice"`. Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`.
- Started by `startFocus(config)` from Flutter, stopped by `stopFocus()`, by the notification action, or when the user ends the session on the glasses.
- `Wearables.initialize` moves to an `Application` subclass and runs once per process. Registration and permission flows that need an Activity stay in a small `ActivityBridge` in `MainActivity`.
- Ongoing notification text: "FocusLens poslouchá posledních 3 min" (FocusLens is listening to the last 3 min). Actions: **Pomoc** (Help), **Pauza** (Pause), **Ukončit** (End). The Help action is the fallback trigger that works without unlocking the phone.
- **Done when** a focus block keeps working (pinch → hint) with the screen locked for 30 minutes, and after the app is swiped away from recents.

### T1.2 `MicRing`, the audio ring buffer in Kotlin
- `AudioRecord` with `VOICE_RECOGNITION` source, 16 kHz, mono, PCM16, reading on its own thread into a preallocated `ByteArray` of `seconds × 32 000` bytes (180 s = 5.76 MB).
- `snapshot(): ByteArray` returns the audio in chronological order. Port the Dart logic from ARCHITECTURE §3.3 and its unit test (including a chunk larger than the buffer).
- `pause()` / `resume()`: while paused, stop recording **and zero the buffer**, so nothing from before the pause can be sent.
- The buffer length comes from config (60 / 120 / 180 s).
- **Done when** the JVM unit test passes and a manual test confirms that a snapshot taken after 10 s contains about 10 s of audio.

### T1.3 `OpusEncoder`, PCM to OGG/Opus
- `MediaCodec` (`audio/opus`, 24 kbps, 16 kHz mono) feeding `MediaMuxer` with `MUXER_OUTPUT_OGG`, writing to a temp file, then upload with `audio/ogg`.
- Run it **in parallel with `capturePhoto()`** (which takes 1.1–2.0 s), so the encoding time is hidden behind the photo.
- Measure the encoding time for 180 s on the test phone. If it is above 1.5 s, switch to rolling encoding (encode 1-second chunks as they arrive and keep a ring of Opus packets).
- Fallback: if the encoder fails, send WAV (the backend accepts both).
- **Done when** a 180 s snapshot is about 0.5 MB and `smoke.py`-style requests with `audio/ogg` return a correct hint from the VPS. Run one manual check that Gemini understands the OGG audio (the transcript in the card matches what was said).

### T1.4 `GlassesSession`, DAT session, camera, inputs and HUD
- Same lifecycle as the spike: `createSession(SpecificDeviceSelector)` → `STARTED` → inputs → camera (LOW, 7 fps, `compressVideo=true`) → wait for `STREAMING` → display.
- Exposes `state: StateFlow<GlassesState>` (`Disconnected | Connecting | Ready(hasDisplay, hasCamera) | Interrupted | EndedByUser | Error(reason)`), `suspend capturePhoto(): ByteArray?` (JPEG), `show(HudScreen)`, and `inputs: Flow<Gesture>`.
- If the glasses have no display (another Meta model), skip `addDisplay` and set `hasDisplay=false`. The output then goes to TTS (T1.7).
- "Session ended by device" + `STOPPED` → `EndedByUser`. The service then stops the focus block and does **not** restart the session.
- Any other `STOPPED` or interruption (call, notification) → `Interrupted`. Try up to 3 automatic restarts with backoff (2 s, 5 s, 10 s). If they fail, post a phone notification: "Focus přerušen. Obnovit?" (Focus interrupted. Resume?).
- `batteryLow` → HUD message plus audio-only mode (hints without a photo).
- **Done when** these cases behave as described on the real device: an incoming call, the user ending the session on the glasses, Bluetooth turned off and on again.

### T1.5 Gesture → action mapping (`GestureRouter`)
| Gesture | Event | HUD state Idle | HUD state Thinking | HUD state Hint |
|---|---|---|---|---|
| pinch | `Select` | **help** | ignore (show "pořád přemýšlím", still thinking) | **close** → Idle |
| swipe right | `Nav RIGHT` | – | ignore | **next step** (new photo + audio + `previous`) |
| swipe left | `Nav LEFT` | show last hint again | ignore | **previous** hint from the local history (max 5) |
| back | `Back` | – | **cancel** the request | close |
| capture button | `Capture` | help (only if T5.1 confirms it does not conflict with Meta AI capture) | – | – |
| swipe up / down | `Nav UP/DOWN` | unused, reserved | | |

- Keep the 1.5 s debounce and ignore `Drag`.
- The same router handles the fallback triggers: the in-app Help button and the notification Help action.

### T1.6 `HintRunner`, the pipeline
```
help ─► HUD "Dívám se…"  (≤ 300 ms after the pinch)
     ├─► MicRing.snapshot() ─► OpusEncoder        ┐ parallel
     └─► GlassesSession.capturePhoto()            ┘
     ─► POST /hint  multipart {photo, audio(ogg), meta{locale, mode, ts, previous}}
     ─► HUD hint + CardWriter.save(status=done)   (≈ 3–4 s)
```
- Use OkHttp with a 12 s total timeout. On 401, show "Aplikace není přihlášená k serveru" (the app is not authorised with the server) and log it in diagnostics. On 413, re-encode at a lower bitrate once. On 422, 502 or a timeout, show "Uloženo, zkusím později" (saved, I'll try later) and save the card as `pending`.
- No photo (camera down) → send audio only. No audio (paused) → send the photo only. Both missing → HUD "Nic k odeslání" (nothing to send).
- **Next step** (swipe right): new photo + new snapshot, `previous` = the steps already shown for the current hint chain. The result goes into the **same card** as an additional step.
- Record timings per hint (`tPhoto`, `tEncode`, `tUpload+AI`, `tTotal`) in the card JSON and send them to diagnostics.
- **Done when** a pinch on the real device shows a correct hint on the HUD in under 4 s (p50 over 10 attempts on Wi-Fi) and a card appears in the app.

### T1.7 Output: HUD renderer and TTS
- `HudRenderer` builds whole screens from a `HudScreen` sealed class (each `sendContent` replaces the whole screen). Layouts are in §7.
- Hints disappear after 20 s (configurable: 10 / 20 / 40 s / never) or on pinch.
- If the glasses have no display, or "Číst nahlas" (read aloud) is on: call `/tts` and play the WAV through the Bluetooth A2DP route. If `/tts` fails or takes longer than 2.5 s, use Android `TextToSpeech` instead.
- **Done when** both modes work on the device. Test the TTS mode by turning the display off in the developer settings.

### T1.8 `CardWriter` + `RetryWorker`
- Layout (extends ARCHITECTURE §8):
```
filesDir/cards/<yyyy-MM-dd'T'HH-mm-ss>/
  photo.jpg
  audio.ogg            # full buffer or only the last 60 s (setting)
  card.json
filesDir/sessions/<sessionId>.json   # {id, mode, startedAt, endedAt, hintCount}
```
- `card.json`:
```json
{
  "id": "2026-11-14T10-32-05", "sessionId": "…", "mode": "lecture", "locale": "cs",
  "status": "done | pending | failed",
  "steps": [{"text": "…", "at": "…"}],
  "card": {"title": "…", "summary": "…", "correction": "…", "transcript_tail": "…"},
  "reviewed": false, "timings": {"photoMs": 0, "encodeMs": 0, "aiMs": 0, "totalMs": 0},
  "attempts": 1
}
```
- Write atomically (temp file + rename) so the UI never reads half a file.
- `RetryWorker` (WorkManager, network constraint, exponential backoff, at most 5 attempts) re-sends `pending` cards. A late result **only fills the card**. It is never shown on the HUD, because it is stale by then.
- Retention: delete cards older than the configured limit (7 / 30 days / never) when the app starts and once a day.

### T1.9 Flutter ↔ service API (`focuslens/focus`)
| Dart → Kotlin (MethodChannel) | Kotlin → Dart (EventChannel `focuslens/focus/events`) |
|---|---|
| `getStatus()` → snapshot of everything below | `glasses {state, hasDisplay, hasCamera, battery?}` |
| `register()`, `unregister()` | `registration {state}` |
| `startFocus({mode, locale, bufferSec, keepAudio, hudTimeoutSec, readAloud})` | `focus {running, paused, mode, startedAt}` |
| `stopFocus()`, `pauseMic()`, `resumeMic()` | `mic {listening, bufferedSec}` (1 Hz while the screen is open) |
| `help()` (in-app fallback) | `hint {phase: capturing\|thinking\|shown\|failed, text?, cardId?}` |
| `setMode(mode)` (switch mode during a block) | `card {id, status}` (saved or updated) |
| `retryPending()` | `diag {kind, text, ts}` (the Gesture Lab log stream) |
| `requestPermissions()`, `openBatteryOptimizationSettings()` | |

Dart wraps this in one `FocusRepository` class with a `ValueListenable<FocusStatus>`. The UI never calls the channel directly.

---

## 4. Phase 2: Flutter app

### T2.0 Project setup
- Folder structure:
```
lib/
  main.dart, app.dart               # MaterialApp, theme, routes
  theme/                            # tokens.dart, theme.dart (§6)
  l10n/                             # app_cs.arb (default), app_en.arb
  data/focus_repository.dart        # channel wrapper (T1.9)
  data/cards_repository.dart        # reads filesDir/cards, watches for changes
  data/settings.dart                # shared_preferences
  screens/onboarding/ focus/ cards/ settings/ diagnostics/
  widgets/                          # shared pieces (status row, step text, audio bar)
```
- Packages, kept minimal: `shared_preferences`, `path_provider`, `just_audio` (card playback), `intl` + `flutter_localizations`. State: `ChangeNotifier` / `ValueNotifier` + `ListenableBuilder`. No state-management framework until it is needed.
- Bundle the fonts as assets (no runtime `google_fonts` fetch, so the app works offline).
- Backend URL and token come from `android/local.properties` (`focuslens_api_url`, `focuslens_api_token`) into `BuildConfig`, the same way `mwdat_*` already works. They are never committed.
- Clean up the leftovers: the `pubspec` description and the "TODO" applicationId/signing comments.

### T2.1 Onboarding (first run only, about 2 minutes, every step can be skipped and resumed later)
1. **What it does.** One screen, one illustration-free sentence each: "Ťukneš prsty → brýle se podívají a poslechnou poslední 3 minuty → ukážou jeden další krok." (Pinch → the glasses look and listen to the last 3 minutes → they show one next step.)
2. **Privacy and consent.** Plain-language explanation that the audio stays only in phone memory and is overwritten, that nothing is stored until you pinch, that you can pause it, and that you should respect places where recording is not allowed. Checkbox "Rozumím" (I understand) is required to continue.
3. **Permissions.** Microphone, notifications, Bluetooth, then the battery-optimisation exemption with a one-line reason for each. A denied permission shows what will not work and a "Zkusit znovu" (try again) button. It never blocks the whole app.
4. **Connect the glasses.** Detect Developer Mode and registration, show "Otevřít Meta AI" (open Meta AI) → registration → glasses camera permission. Show a live status for each step.
5. **Try it.** "Zkus štípnout prsty." (Try a pinch.) The screen reacts live to the first `Select` event and the HUD shows "Funguje to." (It works.)
6. **Defaults.** Language (cs / en) and the default mode.

### T2.2 Focus screen (home)
Two states: **ready** and **running**. The main action is always the biggest thing on the screen.

```
READY                                   RUNNING
┌──────────────────────────────┐        ┌──────────────────────────────┐
│ FocusLens                    │        │ Přednáška            42:10   │
│                              │        │ ● poslouchám · 3 min  [Pauza]│
│ Co teď děláš?                │        │                              │
│ [Přednáška][ Úkol ][ Stůl ]  │        │ Poslední krok · 10:32        │
│                              │        │ Zapiš si vzorec pro entropii │
│ ┌──────────────────────────┐ │        │ z levé strany tabule.        │
│ │      Začít soustředění   │ │        │                              │
│ └──────────────────────────┘ │        │ ┌──────────────────────────┐ │
│                              │        │ │          Pomoc           │ │
│ Brýle      připojené         │        │ └──────────────────────────┘ │
│ Mikrofon   připravený        │        │ 3 kroky v tomto bloku  →     │
│ Server     dostupný          │        │                              │
│                              │        │            Ukončit           │
├──────────────────────────────┤        ├──────────────────────────────┤
│  Focus     Karty   Nastavení │        │  Focus     Karty   Nastavení │
└──────────────────────────────┘        └──────────────────────────────┘
```
- The mode picker is a segmented control: Přednáška / Úkol / Stůl (lecture / homework / desk). It maps to `meta.mode`.
- The status rows (glasses, mic, server) are plain text. A row only gets colour and a "Opravit" (fix) link when something is wrong, and that link goes straight to the fix.
- Starting a block when the glasses are not connected offers "Spustit bez brýlí" (start without glasses): mic plus the in-app Help button, photo from the phone camera. This is the demo fallback.
- **Ending a block** shows one quiet summary sheet: duration, number of steps, "Projít karty" (review cards). Nothing else.

### T2.3 Cards (the evening review)
- **List**: grouped by day, then by focus block (a quiet header such as "Přednáška · 9:00–10:30"). Each row shows a photo thumbnail, the title, the time and the first step. A `pending` card shows a small "Čeká na server" (waiting for the server) text, not a red badge. Reviewed cards are dimmed, never hidden.
- **Detail**, top to bottom:
  1. the photo (tap → fullscreen with `InteractiveViewer` zoom)
  2. **Krok** (step): the step(s) in large text, in the order they were shown
  3. **Oprava** (correction), if not empty, with a marker-yellow underline (§6)
  4. **Co se stalo** (what happened): the summary
  5. **Co zaznělo** (what was said): `transcript_tail` in a quieter style
  6. audio bar: play/pause, scrubber, a "Posledních 60 s" (last 60 s) jump button, and speed 1× / 1.25× / 1.5×
  7. actions: **Hotovo** (done, marks it reviewed), **Přečíst nahlas** (read aloud, TTS of the card text for dyslexic users), **Sdílet** (share: text + photo through the Android share sheet), **Smazat** (delete, with undo in a snackbar instead of a confirmation dialog)
- A `failed` card offers "Zkusit znovu" (try again).
- Empty state: one line, "Tady se objeví, s čím ti brýle pomohly." (What the glasses helped you with will show up here.)

### T2.4 Settings
Sections, in this order:
- **Brýle** (glasses): connection status, re-register, unregister, HUD auto-hide (10 / 20 / 40 s / never), "Číst nahlas" (read aloud).
- **Poslech** (listening): buffer length 60 / 120 / 180 s, what to keep on the card (nothing / last 60 s / everything).
- **Učení** (learning): default mode, language of the hints.
- **Soukromí** (privacy): delete cards after 7 / 30 days / never, **Smazat všechna data** (delete all data, with typed confirmation), a link back to the privacy explanation.
- **O aplikaci** (about): version. Tapping it 5× unlocks Diagnostics.

### T2.5 Diagnostics (hidden)
- Port the Gesture Lab log: the `diag` event stream, copy log, clear.
- Latency table for the last 20 hints (photo / encode / AI / total, with p50 and p90).
- Server check: `/health`, a test `/hint` with 3 s of silence, and the token status.
- Toggle "HUD vypnutý" (HUD off) to test the TTS path.

### T2.6 Accessibility and text
- Use the system text scale up to 200 % without clipping (test at 1.0, 1.3 and 2.0).
- Every tap target is at least 56 dp. Every icon button has a semantic label.
- Respect "remove animations". Do not rely on colour alone (status always has a word).
- Copy is short, informal Czech (tykání), active voice, with no exclamation marks and no "AI" in the UI text. Error messages say what happened and what to do next ("Server neodpovídá. Karta se uložila a odešle se sama." = The server is not responding. The card was saved and will send itself.).

---

## 5. Phase 3: verify on hardware (open questions from ARCHITECTURE §4)

| ID | Test | Pass criterion |
|---|---|---|
| T5.1 | `Capture` button vs. Meta AI's own photo capture | decide whether to map it to help or leave it alone |
| T5.2 | Gestures while the HUD is dimmed / asleep | a pinch after 5 min idle still triggers help |
| T5.3 | Interruption by a call or notification | auto-resume works, or the user gets the resume notification |
| T5.4 | **90-minute block**, phone locked in a pocket | no crash, the buffer runs the whole time, hints work at minutes 5, 45 and 85. Write down the glasses' and the phone's battery drain |
| T5.5 | Gestures the system reserves | list them in ARCHITECTURE §4 |
| T5.6 | Latency on LTE vs. Wi-Fi, with Opus | p50 under 4 s on Wi-Fi, under 6 s on LTE |
| T5.7 | Czech lecture recorded from a pocket | the transcript tail is usable. If not, try the mic source `MIC` vs. `VOICE_RECOGNITION`, or `HINT_MODEL=gemini-3.6-flash` |
| T5.8 | No network for 10 min, then back | the pending cards fill in by themselves, with no HUD spam |

Write the results back into ARCHITECTURE.md (the "✅ Ověřeno" sections), as was done for the camera.

---

## 6. Visual design

**Direction: "study desk".** Paper, ink, a chalkboard and a highlighter. Calm, readable, a bit analogue. It should feel like a good notebook, not like an AI product.

**Avoid (the typical generated-app look):**
- purple/blue/pink gradients, neon on black, glassmorphism and blur cards
- sparkle ✨ icons, robot or brain logos, "AI-powered" badges, chat bubbles
- an indigo accent on white Inter text with big soft drop shadows on every card
- emoji in headings, confetti, streak flames, progress rings
- a dashboard full of stats and charts

### Colour tokens
| Token | Light | Dark | Used for |
|---|---|---|---|
| `paper` (background) | `#F3EFE6` warm paper | `#161917` board black | screen background |
| `surface` | `#FBF9F4` | `#1F2420` | sheets, card rows |
| `ink` (text) | `#1D1C1A` | `#E8E4DA` chalk | main text |
| `graphite` (secondary text) | `#5F5B54` | `#A39E93` | metadata, transcript |
| `rule` (lines) | `#DDD6C8` | `#2F3530` | dividers, outlines |
| `board` (primary) | `#2E4A3B` chalkboard green | `#7FA78F` | main buttons, selected mode |
| `onBoard` | `#F3EFE6` | `#101311` | text on primary |
| `marker` (accent) | `#E9C46A` highlighter yellow | `#D8B65C` | **only** the "listening" dot, the correction underline, focus rings |
| `brick` (error) | `#A8432F` | `#E07A62` | real errors only |

Rules: the marker yellow appears at most once or twice per screen. There are no gradients and no shadows deeper than elevation 1. Separate things with whitespace and thin `rule` lines, not boxes. Check the contrast of every text token against WCAG AA (4.5:1) in both themes.

### Type
- **Atkinson Hyperlegible Next** (OFL, designed for low-vision readers, helps dyslexic users too) for all UI text. Steps are 22–26 sp with a line height of 1.35, body is 16 sp, metadata 13 sp.
- **IBM Plex Mono** only for times, the timer and diagnostics.
- Left-aligned, at most about 60 characters per line, no justified text, no all-caps paragraphs.

### Shape, icons, motion
- Corner radius 6 dp for buttons and 10 dp for sheets. The main button is full width and 64 dp tall.
- One icon set: **Material Symbols Rounded**, weight 300, used sparingly. Text labels beat icon-only buttons.
- Motion: 150–200 ms fades and size changes only, nothing bounces. The "listening" dot breathes slowly (2 s cycle) and stops when "reduce motion" is on.
- App icon: a simple outline of a lens with a single yellow dot, on chalkboard green. No sparkles.

### HUD (600×600, additive display: black = transparent)
Short text, generous margins, never more than three lines of content. Use `FlexBoxBackground.CARD` only behind hints so the text stays readable over a bright board.

```
IDLE (first 3 blocks)          THINKING                    HINT                          ERROR / OFFLINE
                                                           ┌────────────────────────┐
                                                           │ Na třetím řádku ti      │
                                Dívám se…                  │ vypadlo mínus. Oprav    │    Uloženo,
                                                           │ ho a vynásob dvěma.     │    zkusím později.
                                                           │                         │
 ● FocusLens · pinch = pomoc                               │ 2/3   → dál · pinch ✕   │    pinch ✕
                                                           └────────────────────────┘
IDLE (later): only the dot ●
```
- Hint text: `TextStyle.HEADING` up to 12 words, `BODY` above that. The footer is `META`.
- The step counter ("2/3") only appears once there is more than one step.
- PAUSED: a small "Pauza" text in place of the dot, so the person always knows the mic is off.

---

## 7. Backend: small follow-ups (it already does what the app needs)

| ID | Task |
|---|---|
| B1 | Confirm `audio/ogg` (Opus) end to end on the VPS (a real recording, not silence). Add an OGG case to `smoke.py` |
| B2 | Return `X-Request-Id` and `Server-Timing` (Gemini ms) headers, so diagnostics can split network time from AI time |
| B3 | Accept an optional `meta.card_id` and log it (metadata only), so retries can be matched in the logs |
| B4 | Later, before more than a few testers: per-device tokens instead of one shared `APP_TOKEN` (the shared token can be pulled from the APK), and a simple rate limit per token |

---

## 8. Phase 4: release to testers

- T4.1 Release signing (a keystore outside the repo, `key.properties`), `versionCode` from CI or by hand, R8 on with keep rules for the DAT classes.
- T4.2 Project in the Wearables Developer Center: real `APPLICATION_ID` / `CLIENT_TOKEN` in `local.properties`, release channel, invite testers (max 100).
- T4.3 Tests:
  - JVM unit tests: `MicRing`, the gesture router state machine, `card.json` round trip, retry backoff.
  - Flutter widget tests: Focus screen states, the cards list grouping, settings persistence.
  - A fake `/hint` server (or the existing `FakeAI` approach) for the pipeline integration test.
- T4.4 Update the docs: ARCHITECTURE.md §1/§3/§9 for the native hot path, the focuslens README with how to run it, and a short **demo checklist** (charge both devices, Developer Mode, a sheet of paper with a planted error, Wi-Fi hotspot, "start without glasses" as a backup).

---

## 9. Out of scope for this milestone

- **iOS.** No Mac in the setup, and the DAT docs say App Store publishing is not supported yet. The Flutter UI layer is portable. The hot path would need a Swift `FocusService` equivalent later.
- Accounts, cloud sync, search across cards (`gemini-embedding-2`), automatic mode detection, on-device models, annotated photos. These are all listed in GOOGLE_MODELS.md as later options.

---

## 10. Coverage check: idea.md → tasks

| Idea | Covered by |
|---|---|
| Trigger with no phone in hand | T1.4, T1.5 (Neural Band pinch), T1.1 (notification Help as fallback) |
| Backward audio buffer | T1.2 (180 s, configurable), T1.3 (compressed upload) |
| A photo of what the user sees | T1.4 (camera streams the whole block, `capturePhoto`) |
| One concrete next step on the display or in the ear | T1.6, T1.7, §6 HUD |
| "And what next?" without starting over | T1.5 swipe right, T1.6 `previous` |
| Async context card, reviewed in the evening | T1.8, T2.3 |
| Battery-friendly | LOW 7 fps stream, phone mic, AI only on pinch. Measured in T5.4 |
| Privacy of the people around | T1.2 RAM only + pause clears it, T1.1 notification, T2.1 consent, T2.4 retention |
| No long paragraphs, no full solutions | backend prompt (done), 20-word HUD rule, §2 |
| Discreet, reduces shame | silent HUD by default, no sounds, read-aloud is opt-in, no guilt mechanics (§2) |
| Dyslexia support | Atkinson Hyperlegible, read-aloud for cards, large step text |
| 30-second live demo | "start without glasses" mode (T2.2), demo checklist (T4.4) |

**The app is finished when** every row above works on the real glasses, T5.1–T5.8 have results written into ARCHITECTURE.md, and a 90-minute block passes with the phone locked in a pocket.

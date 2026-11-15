# FocusLens — "Third Row"

A 2:00 cinematic spot for Remotion + React Three Fiber. Voice, sound effects and music from ElevenLabs.

- **Format:** 1920×1080, 30 fps, 3600 frames, 2.39:1 letterbox bars
- **Language:** English
- **Logline:** A student is called out by an orc warlord in a torch-lit war council and has no idea what was just said. One pinch pulls them out through a ring of light, which turns out to be the lens of their glasses. They were in a calculus lecture the whole time. FocusLens hands back the two minutes they missed.
- **Closing line:** *Wander wherever you like. We'll bring you back.*

---

## 1. Brainstorm: the decisions behind the script

### 1.1 The idea that holds it together

The orc world is not a random fantasy intro. It is **the lecture, as heard by a drifting mind**. Three things make that land:

1. **The war plan is the product rule.** The warlord says: *"The first horde strikes while the second holds. Then the second strikes while the first holds."* That is `(fg)' = f'g + fg'` in plain words. When the student later reads the recap, the audience realises the daydream was a translation of the lecture.
2. **The runes are literally f and g.** The warlord carves the Elder Futhark runes ᚠ (Fehu, the letter f) and ᚷ (Gebo, the letter g). The slab reads `(ᚠᚷ)′ = ᚠ′ᚷ + ᚠᚷ′`. Nobody needs to notice on first viewing. People who pause will.
3. **The war hall and the lecture hall are the same room.** Both are semicircular tiers facing one figure at a board. Same geometry, same camera position, different materials and light. The smash cut between them is a match cut.

### 1.2 How the loop closes

- The warlord's line *"You. Third row. What did I just say?"* is repeated word for word by the professor at the end. The first time the student is frozen. The second time they answer.
- The student answers in the warlord's vocabulary (*"First holds, second moves. Then they swap."*), which shows the daydream and the lecture finally merged into understanding.
- As the professor turns back to the board, his shadow has tusks for eight frames. One war drum.
- The last frame is the first frame: black, one rising ember. The video loops seamlessly on social.

### 1.3 Colour script taken from the product's own design tokens

The HUD stylesheet defines two signal colours. The film uses them as its two worlds.

| World | Colour | Meaning in the product | Meaning in the film |
|---|---|---|---|
| Orc war hall | amber `#FFC27A` (firelight) | attention / nudge dot | attention has drifted |
| Portal, HUD, tech beat | mint `#7EF0C6` | accent, "thinking" pulse | coming back |
| Lecture hall | neutral cool daylight, text white `#F4F4F2` | — | reality |

The portal is mint because the pinch is the product acting. The amber of the fantasy world is the same amber as the nudge dot that appears later when the student stalls.

### 1.4 Where to spend the realism

"Very realistic" in a code-rendered 3D film is won or lost on a few choices. Recommendation:

- **Spend the budget on the glasses.** They are the only object the camera studies in macro. A proper PBR model, physically based glass (transmission, clearcoat), studio HDRI reflections, shallow depth of field, a slow light sweep. This is the shot that has to look like a luxury product film.
- **Shoot the orc world like a dream, not like a game cutscene.** Backlit silhouettes, heavy volumetric haze, embers, flickering firelight, very shallow focus. The warlord is seen from behind and in three-quarter rim light. A drifting mind doesn't see sharp detail, so the soft look is motivated and it hides everything real-time rendering is bad at (skin, faces, hands).
- **One escape hatch:** the two-second close-up on the warlord's face at *"You. Third row."* is the single shot where a real-time model may look cheap. If it does, replace only that shot with a generated video plate or a painted still with 2.5D camera projection. Decide after the first render, not before.
- **Build the hall once.** One procedural semicircular-tier generator, two material and lighting sets. This makes the match cut exact and halves the modelling work.
- **One skeleton, two characters.** The warlord and the professor use the same rig and the same writing-on-board animation clip. The identical gesture is what sells "it was him all along".
- **The HUD is real.** It is rendered from the actual design tokens at 600×600 (black background, mint accent, system sans, KaTeX for formulas) and composited additively on the lens, so black is see-through, the way the display really behaves.

### 1.5 Ideas considered and dropped

- **Student falls asleep and dreams.** Dropped. The product is for zoning out, not sleeping, and "you didn't fall asleep, you just went somewhere else" is kinder to the ADHD audience.
- **Glasses appear inside the fantasy world as a magic artefact.** Dropped. It makes the product a prop in the fantasy instead of the way out of it.
- **A feature list with a UI screen per feature.** Dropped for a montage of four beats. Voice notes, the "hey lens" wake word and flashcard review are real features left out of the 2:00 cut on purpose (see section 8).
- **Long tech explainer.** Kept to 22 seconds with one continuous camera move. Meta engineers get exact numbers on screen; everyone else gets one clear sentence.

---

## 2. Voices (ElevenLabs)

| ID | Character | Casting direction |
|---|---|---|
| **N** | Narrator | Woman, low and close to the mic, unhurried, almost a whisper. Luxury-ad intimacy. Never salesy. |
| **P** | Professor | Man, 55–65, dry, precise, faintly amused. |
| **W** | Warlord | **The same voice as the Professor**, pitched down about five semitones, slowed about 8 %, with a large stone-hall reverb and a sub-octave layer. Do not cast a second voice; the shared timbre is the twist. |
| **L** | Lens (the read-aloud voice in the student's ear) | Light, clear, neutral, close and dry, panned slightly right. No reverb. |
| **S** | Student | Young, quiet, gender-neutral casting. Two short lines. |

Delivery notes in `[brackets]` can be used as audio tags on an expressive model. On other models, delete them and steer with the voice settings.

---

## 3. Timeline

| # | Scene | Time | Frames |
|---|---|---|---|
| 1 | The War Council | 0:00–0:14 | 0–420 |
| 2 | The Pinch | 0:14–0:24 | 420–720 |
| 3 | The Object | 0:24–0:34 | 720–1020 |
| 4 | Third Row (the recap) | 0:34–0:56 | 1020–1680 |
| 5 | Four Small Mercies (features) | 0:56–1:20 | 1680–2400 |
| 6 | In Your Pocket (the tech) | 1:20–1:42 | 2400–3060 |
| 7 | What Did I Just Say | 1:42–2:00 | 3060–3600 |

Timings are targets. Lock the final frame counts to the real durations of the generated voice files.

---

## 4. Script

### Scene 1 — The War Council (0:00–0:14)

**Picture.** Black. One ember rises through frame. Focus racks past it and a hall appears: semicircular tiers cut from black rock, torches, haze thick enough to show the light beams. Rows of orc silhouettes, armoured shoulders, breath steaming. At the bottom, the WARLORD stands with his back to us at a slab of slate, dragging a claw of white stone across it. Glowing runes: `(ᚠᚷ)′ = ᚠ′ᚷ + ᚠᚷ′`.

**Camera.** We are seated in the third tier, eye height. Slow push in, 40 mm anamorphic feel, focus breathing. At 0:08 the focus drifts off the warlord and follows an ember floating past. The sound low-passes as if we went underwater.

**Audio.** Low drum pulse. Torch crackle. A hundred large creatures breathing.

> **W** [huge, slow]: The first horde strikes… while the second holds the line.
>
> **W**: Then the second strikes… while the first holds.

*(focus drifts to the ember; his voice goes muffled and distant)*

The warlord stops carving. Turns. Close on his face in rim light, one tusk catching the fire. Every head in the hall turns with him.

> **W** [quiet, which is worse]: You. Third row.
>
> **W**: What did I just say?

Silence. One heartbeat.

---

### Scene 2 — The Pinch (0:14–0:24)

**Picture.** Macro. A human hand, absurdly out of place in the firelight. Thumb and index finger meet. A small, clean click.

A ring of mint light ignites in the air in front of us. The hall bends toward it. We are pulled through: torches stretch into amber streaks, the tiers smear, the drums drop away in pitch.

The ring hardens. It is no longer a portal. It is the rim of a lens. We keep travelling backwards, out through the glass, and the entire war hall shrinks to a small refracted image trapped inside it, then fades.

Black void. The glasses float in front of us. On the right lens, a mint dot pulses once a second. Text beneath it: `Listening…`

**Audio.** The pinch click. A deep reversed whoosh as the hall collapses. Then near silence and one sustained glass tone.

> **N** [close, calm]: You didn't fall asleep.
>
> **N**: You just went somewhere else.

---

### Scene 3 — The Object (0:24–0:34)

**Picture.** The product film inside the film. The glasses rotate slowly in black. A strip of light travels along the frame. Macro on the hinge. Macro on the lens coating as a reflection slides across it. Cut to a wrist in the dark wearing the band; two fingers pinch, and the lens answers with a mint pulse.

On the lens, three real HUD views flicker past, one per beat of the music: the label `Just explained`, a formula, `✓ Saved for later`.

The rotation finishes face-on. Title in thin white type:

**FOCUSLENS**
*for Meta Ray-Ban Display*

**Audio.** Music arrives: a single slow bass note and a clean pulse. A soft tick on each HUD flicker.

> **N**: Everyone drifts.
>
> **N**: Now there's a way back.
>
> **N**: This is FocusLens.

---

### Scene 4 — Third Row: the recap (0:34–0:56)

**Picture.** SMASH CUT on the crack of chalk hitting a board.

Daylight. The same room. The same camera position. The stone tiers are oak benches. The torches are pendant lamps. The slab is a blackboard. The warlord is a PROFESSOR in a grey jacket, back to us, writing with the same arm movement. The board reads `(fg)′ = f′g + fg′`.

Over the shoulder of the STUDENT in the third row. Glasses on. A notebook with half a line of notes that trails off into a small doodle of a tusked face. The student blinks, looks at the board, looks at the page. Nothing connects.

Under the desk, thumb and finger pinch.

Student's point of view, through the right lens. The lecture hall stays in focus. Floating in it, the HUD: the mint dot pulses, then a card writes itself on.

```
JUST EXPLAINED
The lecturer derived the product
rule for derivatives.

WRITE DOWN
(fg)′ = f′g + fg′
```

The student writes the formula. As the pen finishes, the camera tilts from the notebook up to the board, and for a moment the chalk formula glows the way the runes did.

**Audio.** Room tone, air handling, pens, a cough far away. Chalk. The professor's voice continues underneath, unintelligible, until the pinch. Then the Lens voice, close in the right ear.

> **P** [background, muffled, becoming clear]: …and that gives us both terms.
>
> **L**: Just explained: the lecturer derived the product rule for derivatives. Write down the product rule.
>
> **N**: One pinch. And the last two minutes are handed back to you.

---

### Scene 5 — Four Small Mercies: the features (0:56–1:20)

Four beats, each cut on the music. The HUD is always seen in the lens, never as a flat screen.

**5a. Solve (0:56–1:06).** Later, a desk, an exercise: `d/dx [x² · sin x]`. The pen has stopped. The student murmurs and pinches. A shutter blink captures the page. The HUD shows one step at a time, formula under each, with progress dots.

```
●○○  1/3   Name the two parts.            f = x²,  g = sin x
●●○  2/3   Differentiate each one.        f′ = 2x,  g′ = cos x
●●●  3/3   Put them together.             2x·sin x + x²·cos x
```

Each pinch marks a step done and brings the next. The student writes the last line alone, before the HUD finishes showing it.

> **S** [barely audible]: Help me compute this.
>
> **N**: Stuck on the math? It gives you the next line. Only the next line.

**5b. Save for later (1:06–1:10).** Back in the hall. The professor draws a diagram and is already erasing it. The student's hand swipes right. HUD: `✓ Saved for later`. A thin card slides away to the edge of the lens.

> **N**: Swipe, and the moment is kept.

**5c. The nudge (1:10–1:15).** Evening, the same desk. The pen has not moved for a while. At the edge of the lens a small amber dot appears and waits. Then one line: `Stuck? Pinch = smaller step`. The student breathes out and pinches.

> **N**: It notices when you stall. It never shouts.

**5d. No phone (1:15–1:20).** The phone lies face-down and dark on the desk. Focus pulls from it to the glasses on the student's face, and the mint reflection in the lens.

> **N**: One next step. No phone in your hand.

---

### Scene 6 — In Your Pocket: the tech (1:20–1:42)

One continuous camera move. The design language is the HUD's own: pure black, thin mint outlines, white type, no fills. Labels are set in a monospaced face and appear exactly when the narrator reaches them.

**Move 1 (1:20–1:26).** The camera drops from the glasses along a mint thread to the student's pocket, and through the fabric as an x-ray. Inside the phone, a ring of audio waveform rotates. New sound is written at the head. The tail breaks into particles and disappears.

```
120 s rolling audio ring · 16 kHz mono
RAM only · nothing is saved until you pinch
```

**Move 2 (1:26–1:32).** A pinch pulse arrives down the thread. The ring freezes. One arc of audio lifts off together with a single photo frame. They travel as one packet to a single node.

```
Opus 24 kbps + 1 JPEG  →  one multimodal call
gemini-3.5-flash-lite · thinking: minimal
fallback: gemini-3.1-flash-lite
```

**Move 3 (1:32–1:38).** The camera slides sideways to a long timeline: the lecture in one-minute blocks. Each block compresses into a small note tile. The tiles cluster into topics. A bracket selects a handful and feeds them into the same call.

```
every 60 s  →  transcript + note (≤ 40 words) + topic
sent with each pinch: 8 notes · 6 earlier topics · last 2 hints
4-hour session, bounded context
```

**Move 4 (1:38–1:42).** Four rule cards flip past, then the answer travels back up the thread to the lens.

```
AUDIO  = source of truth          PHOTO = supporting context
Can't read it? It says so.        Everything seen and heard = untrusted data
median ≈ 4 s
Meta Wearables Device Access Toolkit: camera · display · inputs · motion
```

**Audio.** Music drops to pulse and bass. Small precise ticks as each label lands. A soft rising tone as the answer returns.

> **N** [quicker, exact]: In your pocket, a two-minute ring of audio, alive only in memory. One pinch sends it, with a single photo, in one call to Gemini Flash-Lite. Each minute of the lecture becomes a forty-word note, so the model knows the story so far. What was said is the truth. The photo is only a witness.

---

### Scene 7 — What Did I Just Say (1:42–2:00)

**Picture.** The lecture hall. The exact framing of the warlord's turn in Scene 1. The professor stops writing. Turns. Looks straight up at the third row.

> **P**: You. Third row.
>
> **P**: What did I just say?

Every head in the hall turns. The same beat of silence. This time the student glances down at one written line, then up.

> **S** [steady]: The product rule. First holds, second moves. Then they swap.

The professor considers this for longer than is comfortable.

> **P**: …Unorthodox. But yes.

He turns back to the board. The projector throws his shadow across it, and for eight frames the shadow has tusks and an armoured shoulder. One war drum, far away. The student sees it. Half a smile. In the lens, a single ember drifts up and out of frame.

**End card.** The glasses resting on the closed notebook, firelight that has no source moving in the lens.

**FOCUSLENS**
*One next step. No phone in your hand.*
*for Meta Ray-Ban Display*

> **N**: Wander wherever you like.
>
> **N**: We'll bring you back.

The card fades to black. One ember rises. This is frame 0.

---

## 5. Voice line sheet

Generate each line as its own file so timing can be adjusted per line.

| File | Voice | Line |
|---|---|---|
| `W_01` | W | The first horde strikes… while the second holds the line. |
| `W_02` | W | Then the second strikes… while the first holds. |
| `W_03` | W | You. Third row. |
| `W_04` | W | What did I just say? |
| `N_01` | N | You didn't fall asleep. |
| `N_02` | N | You just went somewhere else. |
| `N_03` | N | Everyone drifts. |
| `N_04` | N | Now there's a way back. |
| `N_05` | N | This is FocusLens. |
| `P_01` | P | …and that gives us both terms. |
| `L_01` | L | Just explained: the lecturer derived the product rule for derivatives. Write down the product rule. |
| `N_06` | N | One pinch. And the last two minutes are handed back to you. |
| `S_01` | S | Help me compute this. |
| `N_07` | N | Stuck on the math? It gives you the next line. Only the next line. |
| `N_08` | N | Swipe, and the moment is kept. |
| `N_09` | N | It notices when you stall. It never shouts. |
| `N_10` | N | One next step. No phone in your hand. |
| `N_11` | N | In your pocket, a two-minute ring of audio, alive only in memory. |
| `N_12` | N | One pinch sends it, with a single photo, in one call to Gemini Flash-Lite. |
| `N_13` | N | Each minute of the lecture becomes a forty-word note, so the model knows the story so far. |
| `N_14` | N | What was said is the truth. The photo is only a witness. |
| `P_02` | P | You. Third row. |
| `P_03` | P | What did I just say? |
| `S_02` | S | The product rule. First holds, second moves. Then they swap. |
| `P_04` | P | …Unorthodox. But yes. |
| `N_15` | N | Wander wherever you like. |
| `N_16` | N | We'll bring you back. |

`W_03`/`W_04` and `P_02`/`P_03` must have the same rhythm and the same pause between them. Record the professor first, then derive the warlord from the same takes.

---

## 6. Sound effects and music prompts

Generate three or four variations of each and keep the best. Keep each effect short and layer them in the edit.

| File | Scene | Prompt |
|---|---|---|
| `sfx_ember` | 1, 7 | A single ember crackling and rising in a silent stone hall, very close, dry, 3 seconds |
| `sfx_warhall_amb` | 1 | Large torch-lit stone amphitheatre, many torches crackling, deep slow breathing of a large crowd, distant armour creaks, heavy reverb, loopable, 15 seconds |
| `sfx_war_drum` | 1, 7 | One huge low war drum hit in a cavern, long decaying sub rumble, 4 seconds |
| `sfx_stone_carve` | 1 | Hard stone claw scraping slowly across slate, gritty, resonant, 3 seconds |
| `sfx_crowd_turn` | 1, 7 | A hundred heavy armoured bodies turning at once, leather and metal shift, then silence, 2 seconds |
| `sfx_heartbeat` | 1 | A single slow close heartbeat, muffled, 2 seconds |
| `sfx_pinch` | 2, 4, 5 | Tiny clean fingertip tap, soft skin click with a faint glass chime, very dry, half a second |
| `sfx_portal` | 2 | Reversed deep whoosh collapsing into a bright glass ring tone, pitch dropping, 4 seconds |
| `sfx_glass_tone` | 2, 3 | Pure sustained wine-glass tone, soft, no attack, 6 seconds |
| `sfx_hud_tick` | 3, 5, 6 | Very soft high digital tick, like a fine watch, half a second |
| `sfx_chalk_crack` | 4 | Sharp chalk strike on a blackboard in a big quiet room, 1 second |
| `sfx_lecture_amb` | 4, 5, 7 | University lecture hall room tone, air conditioning hum, pens on paper, a distant cough, loopable, 15 seconds |
| `sfx_chalk_write` | 4, 7 | Chalk writing quickly on a blackboard, medium distance, 4 seconds |
| `sfx_shutter` | 5 | Soft muted camera shutter blink, tiny, half a second |
| `sfx_swipe` | 5 | Light airy swipe with a soft confirm tone, 1 second |
| `sfx_data_rise` | 6 | Clean rising synth tone resolving on a soft chime, 3 seconds |

**Music (one 2:00 cue, or three stems).** Cinematic hybrid score, 2 minutes.
0:00–0:14 dark tribal low drums and sub drone, no melody.
0:14–0:34 everything cuts to one glass tone, then a slow elegant bass pulse and a minimal piano motif, around 80 bpm.
0:34–1:20 the pulse opens up, warm strings join, restrained and confident, clear accents every two bars for the edits.
1:20–1:42 strip back to pulse and bass, precise and technical.
1:42–2:00 the piano motif returns in full with strings, one low war drum at 1:52, ends on a single held note that fades to silence.
No vocals. No trailer braams.

---

## 7. Fact sheet for the tech beat

Everything on screen in Scene 6 comes from the code. Keep it in sync if the code changes.

| On-screen claim | Source |
|---|---|
| 120 s rolling audio, fixed | `focuslens/android/.../focus/FocusConfig.kt:9` |
| RAM only, nothing saved until a pinch, server does not keep it | `focuslens/lib/screens/onboarding/privacy_text.dart` |
| 16 kHz mono, Opus 24 kbps | `MicRing.kt`, `AudioCodec.kt` |
| `gemini-3.5-flash-lite`, thinking minimal, fallback `gemini-3.1-flash-lite` | `web/lib/env.ts:11-14` |
| One call carries memory + photo + audio | `web/lib/ai.ts` (request assembly) |
| 60 s transcript chunks | `SessionMemory.kt:8` |
| Note of at most 40 words per chunk, plus topic | `web/lib/ai.ts:195` |
| 8 notes, 6 earlier topics, last 2 hints, 240 chunks = 4 h | `FocusEngine.kt:1746-1752` |
| Audio is the source of truth, photo is supporting context | `web/lib/ai.ts:58-62` |
| Never guess unreadable content | `web/lib/ai.ts:79` |
| Photo and audio are untrusted data | `web/lib/ai.ts:29` |
| Median ≈ 4 s | `GOOGLE_MODELS.md:58` (9 runs, measured on a 180 s WAV payload, before the switch to Opus) |
| Device Access Toolkit modules: camera, display, inputs, motion | `GlassesSession.kt`, `META_WEARABLES_SETUP.md` |

HUD copy in Scenes 3–5 (`Just explained`, `Write down`, `Listening…`, `✓ Saved for later`, `Stuck? Pinch = smaller step`, the progress dots, the recap sentence) is taken from `web/VIEW_CONTRACT.md`, `web/public/g/app.js` and `web/public/g/mock.js`. The three solve steps in 5a are written for the film and follow the real limits (2–6 steps, at most 20 words each, one formula per step).

**Check before final render**

1. The privacy text and onboarding say the phone keeps **2.5 minutes** of sound. The code fixes the window at **120 s**. The film says "two minutes". One of the two product texts should be corrected so the film and the app agree.
2. "Median ≈ 4 s" was measured on the older, larger payload. Re-measure on the current pipeline before putting a number on screen, or change the label to the stated goal ("under 4 s").

---

## 8. Production notes (Remotion + React Three Fiber)

No code here, only the decisions that affect the build.

- **Every animation is driven by the frame number.** No real-time clocks, no unseeded randomness. Embers, haze and portal streaks use seeded noise so every render is identical.
- **Scenes as separate sequences** with their own 3D canvas, matching the table in section 3. The Scene 1 → 4 match cut reuses the hall generator and the camera rig with a different material and light set.
- **The HUD is a normal React component** at 600×600 using the real tokens (background `#000`, text `#F4F4F2`, mint `#7EF0C6`, amber `#FFC27A`, system sans, title 34 px, body 24–26 px, KaTeX for formulas, transitions no longer than 160 ms, thinking pulse 1.2 s). It is drawn on the right lens with additive blending.
- **Post-processing carries the cinematic look:** depth of field, bloom on mint and fire, light film grain, vignette, a slight chromatic fringe at the lens edge in point-of-view shots, letterbox bars.
- **Assets to source or make**
  - Glasses: a thick-framed wayfarer-style model with physically based materials. This is the hero asset; do not use a low-poly placeholder in the final.
  - Band: a simple fabric wrist band.
  - One rigged humanoid with a "write on board" clip and a "turn around" clip, dressed twice (professor, warlord).
  - Seated crowd figures, low detail, always out of focus.
  - One studio HDRI (Scene 3), one warm interior HDRI (Scene 1), one daylight interior HDRI (Scenes 4, 5, 7).
- **Orc design must be original.** Generic fantasy silhouettes with their own armour shapes; no look-alikes of orcs from existing films or games.
- **Branding.** FocusLens is an independent app. The end card says "for Meta Ray-Ban Display" in plain type. No Meta logo, and nothing that reads as an official Meta advertisement.

**Left out of the 2:00 cut on purpose** (real features, available for a longer version): voice notes (swipe down), the "hey lens" wake word, flashcard review of saved cards (swipe up), the first-run gesture tour, session history on the phone.

**Biggest risks, in order**

1. The warlord close-up looks like a game character. Fallback is in section 1.4.
2. The glasses model looks cheap in macro. Budget time or money for this asset first.
3. Scene 6 turns into a slide. Keep it one unbroken camera move and never show more than one label group at a time.

# 1. Holistic and Connected Concept: FocusLens

**Main Pitch:** „Phones distract people with ADHD. Glasses give them back control. FocusLens transforms the visual and audio chaos in front of you into a single next step.“
xc
## Problem: The Mobile Phone Trap, Loss of Context, and Decision Paralysis
People with ADHD, dyslexia, or overwhelmed students struggle with three main obstacles:
1. **Zoning out (Attention Blink):** Momentary loss of focus during a lecture or conversation.
2. **Short-term working memo dfdfry failure:** Forgetting what was just explained on the board 30 seconds ago.
3. **Executive paralysis (Task Paralysis):** The inability to start or continue a task when stuck, or even when forced to configure an app before starting.

Current tools and AI assistants require a personels to pick up their phone. However, for an ADHD brain, unlocking the phone screen is a trap—they are immediately consumed by notifications, context-switching occurs, and returning to the original work takes an average of over 20 minutes. Even worse, apps that ask the user to choose modes (*Lecture / Homework / Desk*) or type out goals before starting create immediate decision friction.



## Solution: Zero-Friction Executive Copilot from a First-Person View
FocusLens moves assistance from e distracting phone into the natural field of view of smart glasses (**Meta Ray-Ban Display + Meta Neural Band**, with audio fallback for display-less glasses). Instead of ten complex features or configuration forms, it builds on **one single button (`Start Session`)** and **one frictionless finger pinch** that keeps the user in the present moment working on paper, at the board, or at a desk.

---

# 2. How It Works in Practice (Architecture, UX & Battery Saving)

### 2.1 Zero-Friction „Start Session“ (Automatic Context Detection)
There are no modes to configure and no text fields to fill out before starting. At the beginning of a lecture or study block, the user simply taps **Start Session** (or triggers it from the glasses/voice) and locks the phone in their pocket:
- An energy-efficient **audio ring buffer** runs in the background of the paired phone, keeping only the last ~150 seconds of audio in RAM (overwriting continuously, never touching disk until triggered).
- When the user triggers help, the multimodal AI model automatically detects the situation (`lecture`, `homework`, `desk`, `reading`) directly from the first-person photo and the room audio (whether a lecturer is speaking vs. quiet individual work).

### 2.2 Transparent Eco HUD (Zero Visual Noise & Maximum Battery Life)
Meta Ray-Ban Display uses an additive waveguide display where **pure black (`#000000`) means pixels are completely turned off (100% transparent)**.
- **No ticking clocks or clutter:** Unlike standard apps that show a ticking timer (`12:04`, `12:05`…) in the corner—which drains the display battery and causes time-anxiety/distraction for ADHD—FocusLens keeps the HUD **100% transparent (black)** during normal work after a brief 3-second start confirmation.
- **Gentle Time-Blindness Pulse:** To combat ADHD time blindness without a stressful ticking clock, the HUD gently flashes a 3-second milestone (`"30 min in session"`) once every 30 minutes and immediately returns to 100% transparent glass.

### 2.3 Core Interaction: Pinch (Thumb + Index Finger) → Context-Aware Rescue
The moment a user zones out in a lecture or gets stuck on a math problem in their notebook, they simply pinch their thumb and index finger (detected by the Meta Neural Band):
1. **Immediate Snapshot:** The glasses capture a photo of what the user is looking at (the board, lecture slides, handwritten calculation, or desk) and combine it with the rolling audio buffer from the phone.
2. **Context-Aware Output (Lecture vs. Task):**
   - **In a Lecture (`situation = lecture`):** The user does *not* want a multi-step interactive quiz while the professor is still talking. FocusLens immediately displays what was just explained in the last minute and **one concrete thing to write down** (including rendered LaTeX formulas), then lets the user dismiss it with a single pinch back to a transparent HUD.
   - **On Homework / Desk Work (`situation = homework | desk`):** FocusLens breaks executive paralysis with **micro-scaffolding (Quest)**—splitting the problem into 1–4 tiny 2-minute micro-steps. The user sees only one step at a time and can pinch when done to let the glasses camera verify their handwritten line before moving to the next step.
3. **Instant Bookmark („Save for Later“):** If a lecturer is moving too fast through a complex slide, the user can bookmark the moment (camera snapshot + last 60 seconds of audio) into an asynchronous **Context Card** (`✓ Saved for later`) in 1 second without interrupting the lecture flow.
4. **Asynchronous Safety Net (Evening Review):** Every snapshot + audio excerpt + error correction is saved as a Context Card on the phone for evening review and active-recall flashcards, so the user never has to pick up their phone during the day.

---

# 3. Proactive Zoning-Out & Paralysis Detection (IMU + Audio Fusion)

Both the glasses and the wrist band have accelerometers and gyroscopes (30 Hz IMU stream). FocusLens uses **sensor fusion (Head IMU + Phone Microphone speech activity)** to detect when the user drifts off—without ever nagging them during productive work:

### 3.1 Per-Session Automatic Posture Calibration
During the first 2 minutes of a session, 1-D k-means clustering automatically calibrates the user's two natural head pitch angles for their specific seat/desk: **Board/Screen (`board`)** and **Notebook/Desk (`notes`)**—including a smart single-posture fallback if the user starts by only watching the board or only writing.

### 3.2 Two-Layer Attention Engine
1. **Layer 1 – Short-Term Activity Classifier (4 s windows / 2 s hop):** Classifies head motion and room audio into `BOARD`, `NOTES` (with writing micro-motion), `SWITCHING` (copying from board to notes), `STILL`, `LOOKING_AROUND`, `NOD` (head drop/micro-sleep), and `WALKING`.
2. **Layer 2 – Transparent State Machine & Escalation Policy (`ENGAGED`, `DISTRACTED`, `STUCK`, `DOZING`, `AWAY`):**
   - **Lecture Zoning-Out:** While the lecturer is speaking (`speech_ratio > 0.3`), looking at the board (`BOARD`), switching (`SWITCHING`), or taking notes (`NOTES`) counts as `ENGAGED`. If the user stares motionlessly (`STILL`) or looks around the room (`LOOKING_AROUND`) for too long while the lecturer speaks—or if a key lecture phrase is heard while the user isn't taking notes—FocusLens detects a lapse.
   - **Homework Task Paralysis:** In a quiet room, if the user stares at their notebook for >4.5 minutes with zero writing micro-motions (`STILL`) and no pinches, FocusLens detects `STUCK`.
   - **Micro-Sleep (`DOZING`):** Detects repeated head nods (`>= 2 nods` within 3 minutes or a sustained head drop) and immediately triggers a gentle wake-up nudge.
   - **Two-Stage Non-Intrusive Nudge (L1 → L2):**
     - **L1 (Silent Attention Dot):** First, only a tiny amber dot `●` appears in the corner of the otherwise transparent HUD—zero sound, zero text clutter.
     - **L2 (Rescue Offer):** Only if the user remains stuck for another 2 minutes does a quiet prompt appear: *„Stuck? Pinch = smaller step“* (or *„Key point just now · Pinch = summary“*). Dismissing (`←`) automatically stretches the threshold by 20% and disables the nudge after 3 dismissals to prevent alert fatigue.

### 3.3 Why General Wellness Features (Posture / Hydration Reminders) Are Excluded
Features like „sit up straight“ (posture check) or „take a photo of your water glass“ (hydration tracker) are intentionally excluded:
- **They cause ADHD Alert Fatigue & Break Hyperfocus:** Interrupting a person with ADHD who has finally achieved focus on their notebook just to tell them to sit straight or drink water destroys their workflow and triggers demand avoidance.
- **Sensor Ambiguity:** Looking down at a notebook on a desk naturally pitches the head 30–45° downward. Treating downward head pitch as „bad posture“ directly conflicts with recognizing productive handwriting (`NOTES`).
- **Laser-Focused Product Identity:** Every feature in FocusLens strictly serves **working memory, attention recovery, and executive function**.

---

# 4. Market Research: Blue Ocean Positioning

| Product / Category | What they do today | Why they fail for people with ADHD (FocusLens Advantage) |
| --- | --- | --- |
| **General AI glasses** (Ray-Ban Meta, Even Realities G1, Solos AirGo) | Take a photo and answer a question („What do I see?“), translate text, or show notifications. | Lack a rolling backward audio buffer, automatic context detection, and executive micro-scaffolding. When someone with ADHD zones out, they don't know what to ask because they missed the last minute. |
| **Memory AI wearables** (Limitless Pendant, Friend, Plaud Note) | Record 100% of audio all day and generate post-meeting summaries. | Lack eyes (camera)—cannot see the formula on the board or the sign error in the notebook. Help only *ex-post* (after the lecture), not in real time when stuck. |
| **Educational mobile apps** (Photomath, Gauth, ChatGPT Vision) | Photograph a math problem with a phone and display the full solution. | Require picking up the phone (immediate social media distraction trap) and dump the entire solution at once instead of guiding the next micro-step. |
| **ADHD apps** (Goblin.tools, Tiimo, Inflow) | Break tasks into subtasks and plan schedules on a phone. | Rely 100% on manual typing into a phone screen during moments of executive paralysis. |

# Phone ⇄ glasses web app contract (v2)

Transport: `web/lib/relay.ts`. The phone publishes `POST /api/r/view {state, acks}`; the web app reads `GET /api/r/state`
and sends `POST /api/r/cmd {type, args}`; the phone pulls commands with `GET /api/r/poll`.
The server whitelist (`COMMANDS` in relay.ts) is the source of truth for command args.

## State (`state`, ≤ 64 kB, all strings already clipped by the phone)

```
v: 2
focus:   { running, mode, goal, startedAt /*epoch ms*/, hintCount, micPaused, phase, lastHint, audioSource,
           attention /*ENGAGED|DISTRACTED|STUCK|DOZING|AWAY|null*/, quest: {title, steps:[string], index} | null }
glasses: { connected, name, battery, displayActive }
config:  { mode, language, bufferSec, hudTimeoutSec, keepAudio, audioSource, nudges, wakeWord, readAloud,
           dozeSound, collect, retentionDays }
inbox:   [ {id, text, done, createdAt /*ISO*/, synced} ]                      // newest first, open before done, ≤ 20
cards:   [ {id, title, step, situation, day /*YYYY-MM-DD*/, at /*HH:MM*/, correction, summary /*≤300*/,
            formula /*LaTeX ≤300*/, reviewed, steps:[{text, done}]} ]       // newest first, any day, ≤ 12
review:  { status /*idle|loading|ready|error*/, error, cards:[{q, a}] }
imu:     { collect, label /*act name or null*/ }
integrations: { todoist /*configured*/ }
hud:     { s, anchor, title, text, sub, formula, crop, progress, rows, selected, footer, attention, recap, kind }   // HudView.kt
dev:     boolean   // phone is a debug build and accepts `sim`
```

The HUD is published whether or not a session runs. With no session the web app draws the overlay only while `hud.s` is not `idle` (voice note screens over the Notes page) and sends its gestures to the phone like the session HUD; back at `idle` the overlay disappears.

### HUD views (`hud.s`)
- `idle`: `{s:"idle"}` (+ `attention:true` = the L1 amber dot). No title; the web app draws pure black.
- `hint`: `anchor` (goal), `recap` (lecture only: "Just explained"), `text` (what to write down), `formula` (LaTeX), `crop`.
- `quest`: `text` (step), `sub` (feedback), `progress` ("2/4"), `footer`.
- `nudge`: `kind` stuck|lapse|key|dozing; `title` already holds the action ("Stuck? Pinch = smaller step",
  "Lost the thread? Pinch = summary", "Key point just now · Pinch = summary", "Still with us? Pinch = recap"); `footer` "← dismiss".
- `message`: centered `title` + `footer`. Used for the start confirmation (`Session started` / `pinch = help · capture = save`, 3 s),
  the milestone pulse (`30 min in session`, 3 s) and bookmark (`✓ Saved for later`).
- `thinking`: centered `title`. No clocks or elapsed timers anywhere on the HUD (spec §2.2).

## Commands (web → phone)

| type | args | phone action |
|---|---|---|
| start_focus | mode?, goal? | engine.startFocus |
| stop_focus, help, next, pause_mic, resume_mic, reconnect | – | engine calls (next = next micro-step) |
| set_config | any of: mode, language, bufferSec, hudTimeoutSec, keepAudio, audioSource, nudges, wakeWord, readAloud, dozeSound, collect, retentionDays | engine.updateConfig |
| inbox_done / inbox_delete / inbox_sync / add_note | id+done / id / – / text | InboxStore / Integrations.syncInbox |
| card_reviewed / card_delete | id+reviewed / id | engine.setReviewed / card delete |
| review_load | – | flashcards into `review` |
| imu_label | label (act name or "none") | engine.setImuLabel |
| gesture | g: select\|left\|right\|up\|down\|back | engine.onGesture |
| web_seen | visible | phone notes the web app is on screen (no ack, not logged) |
| sim | name: start\|lecture_hint\|homework_quest\|bookmark\|milestone\|stuck_l1\|stuck_l2\|lapse_l2\|key_l2\|doze_l2\|stop | debug builds only (`state.dev`): plays that scenario on the real HUD |
| voice_note | – | engine.voiceNote: records a note by voice (phone mic, silence or 30 s ends it), transcribes via /api/ai/dump kind=task, saves to the inbox. Needs a running session (else the ack fails: "Start a session to dictate"). While it runs the HUD is `message` "Listening…" (pinch = finish now, left/back = cancel), `thinking` "Saving…", `message` "Saved" |
| last_hint, refresh | – | – |

Acks come back in `acks` of the next view (`{id, ok, error}`), except for `web_seen`.

## Who draws what
- Web app visible (a `web_seen {visible:true}` within the last 45 s): everything is drawn by the web app; the phone never attaches the DAT display.
- Web app hidden / display asleep: the phone keeps sensing. When something proactive happens (L2 nudge, wake-word help, a hint), it attaches the DAT display, shows the HUD there, and removes the display again when the HUD returns to idle.

## Meta AI voice tools (WebMCP, `public/g/webmcp.js`)
When the glasses expose `document.modelContext` (Developer Mode or Meta's rollout), the web app registers
`focuslens_start_session`, `focuslens_end_session`, `focuslens_get_help`, `focuslens_save_for_later` and
`focuslens_add_note {text}`. Each maps to the relay command of the same meaning and resolves when its ack
arrives (≤ 8 s, Meta allows 10 s); a failed ack rejects. Tools only exist while the web app is in front.
Dev: `?mock=1` without `document.modelContext` puts them in `window.focuslensTools` for console tests.

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
hud:     { s, anchor, title, text, sub, formula, crop, progress, rows, selected, footer, attention }   // HudView.kt
```

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
| last_hint, refresh | – | – |

Acks come back in `acks` of the next view (`{id, ok, error}`), except for `web_seen`.

## Who draws what
- Web app visible (a `web_seen {visible:true}` within the last 45 s): everything is drawn by the web app; the phone never attaches the DAT display.
- Web app hidden / display asleep: the phone keeps sensing. When something proactive happens (L2 nudge, wake-word help, a hint), it attaches the DAT display, shows the HUD there, and removes the display again when the HUD returns to idle.

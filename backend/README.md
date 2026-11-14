# FocusLens backend

FastAPI + Google Gemini. Holds the API key so it never ships inside the APK.
Model research and the reasoning behind the defaults: [../GOOGLE_MODELS.md](../GOOGLE_MODELS.md).

| Endpoint | What it does | Model (default) |
|---|---|---|
| `POST /hint` | photo + audio → one next step + context card (JSON) | `gemini-3.5-flash-lite`, thinking `minimal`; fallback `gemini-3.1-flash-lite` |
| `POST /tts` | text → WAV (24 kHz mono), for glasses without a display | `gemini-3.8-flash-lite-tts`, voice `Kore` |
| `GET /health` | liveness | – |

All non-health endpoints need `Authorization: Bearer <APP_TOKEN>`.

## Contract

```
POST /hint   multipart/form-data
  photo: image/jpeg     (optional)
  audio: audio/wav      (optional, but at least one of photo/audio; ogg/opus/aac also accepted)
  meta:  {"locale":"cs","mode":"auto|lecture|homework|desk","ask":"help|smaller|rescue","ts":"…",
          "goal":"…","mistakes":["…"],"previous":["…"],
          "recent":[{"situation":"homework","title":"…","step":"…"}]}
         goal     = focus anchor of the block (≤ 200 chars)
         mistakes = the user's recurring mistake types, newest last, max 10 (kept on the phone)
         ask      = smaller → split the current step; rescue → what was just said that matters
         previous = steps already shown for THIS card (swipe right "and what next?"), max 5
         recent   = earlier hints of the focus block, oldest first, max 6 (continuity across tasks)

200 {"next_step":"…", "steps":["…","…"],     1-4 micro-steps (quest), steps[0] == next_step
     "formula":"x = \\frac{1}{2}",            LaTeX ("" if none), whitelisted (formula.py)
     "formula_png":"<base64>"|null,          white on black, ≤ 560×160, ready for the HUD
     "focus_box":[ymin,xmin,ymax,xmax]|null, 0-1000 on the photo: where the mistake / key formula is
     "mistake":"Sign error when …"|"",       generic mistake type for the phone's mistake profile
     "card":{"title":"…","summary":"…","correction":"…",
             "transcript_tail":"S1: …\nS2: …",  speaker-labelled lines
             "situation":"lecture|homework|desk|reading|other",
             "speakers":[{"label":"S1","said":"…"}]}}
401 bad token · 413 file too big · 422 no input / bad meta · 502 both models failed (phone saves card as pending)

POST /check  multipart photo (required) + meta
  {"locale":"cs","goal":"…","mistakes":["…"],"quest":{"title":"…","steps":["…"],"index":0}}
200 {"done":true,"feedback":"Sign fixed, nice.","formula":"","formula_png":null,"focus_box":null,"mistake":""}

POST /dump   multipart audio (required, ≤ 2 MB) + meta {"locale":"cs","kind":"task|goal"}
200 {"text":"Buy milk"}                       "" when nothing intelligible was said

POST /review JSON {"locale":"cs","items":[{"title","summary","correction","mistake"}]}  (1-20 items)
200 {"cards":[{"q":"…","a":"…"}]}              up to 5 active-recall flashcards

POST /tts    {"text":"…"}  → audio/wav   (always the one TTS_VOICE; client voice is ignored)
```

## Glasses app menu (web app + relay)

`/glasses/` is a Meta Ray-Ban Display **Web App** (600×600, Neural Band = arrow keys / Enter /
Escape): the full FocusLens UI on the glasses (start/stop focus, mode, goal, blocks, inbox, evening
review, settings). It has no camera/mic, so it remote-controls the phone app through `/remote/*`
(`remote.py`):

```
phone  POST /remote/pair        (APP_TOKEN)  -> device_id, phone_secret, 8-char code (10 min), install_url
glasses POST /remote/claim {code}            -> web_token (code is single-use; 10 tries/min/IP)
phone  GET  /remote/phone/events (SSE)       <- {"id","type","args"} commands, ": ping" every 20 s
phone  POST /remote/phone/state  (≤ 64 KB)   -> latest status + today's notes/cards (memory only)
phone  POST /remote/phone/ack    {id,ok,error}
glasses GET /remote/web/state                -> {phone_online, state_age_s, state, acks}
glasses POST /remote/web/command {type,args} -> 202 {id} | 409 phone offline | 422 not whitelisted
phone  POST /remote/unpair                   -> revokes device + all web tokens
```

`install_url` = `fb-viewapp://web_app_deep_link?appName=FocusLens&appUrl=<web_url>`; opening it on the
phone makes the Meta AI app add FocusLens to the glasses menu (Developer Mode). If the code is lost
on install, the web app's pairing screen lets you type it (pinch → text composer).

Security: secrets/tokens/codes stored only as SHA-256 hashes in SQLite (`/data/remote.db`, compose
volume `focuslens_data`); commands are a fixed whitelist with validated args; live state and the
queue are in memory only and never logged; `/glasses` is served with a strict CSP and
`Referrer-Policy: no-referrer`. Single uvicorn worker (in-memory hub).

**Prompt injection:** the photo/audio are untrusted (text on a board or a voice in the room can
try to instruct the model). Defences are listed at the top of `ai.py`: data-not-instructions
system prompt + `injection_suspected` flag, fixed JSON schema, whitelisted meta, and an output
guard (no links/e-mails/control chars, length caps, hijack phrases → safe fallback step).
LaTeX is rendered by matplotlib mathtext (no TeX engine, no file access) after a command whitelist.

One Gemini call does everything: it hears the audio natively, so there is **no separate STT step**
(the old Claude plan needed one). 180 s of audio = 5 760 tokens.

## Run locally

```bash
cd backend
python -m venv .venv && .venv/Scripts/activate      # Linux/mac: source .venv/bin/activate
pip install -r requirements.txt pytest
cp .env.example .env                                 # fill GEMINI_API_KEY and APP_TOKEN
pytest -q                                            # unit tests, no key needed
uvicorn app:app --reload
python smoke.py http://localhost:8000 <APP_TOKEN> [photo.jpg] [audio.wav]   # live test, prints latency
```

## Deploy to the VPS (lens.projectviridis.dev)

The domain is proxied by **Cloudflare** (DNS resolves to Cloudflare IPs, HTTP → HTTPS redirect is on),
and the origin already answers HTTPS with a 404, so *something* is already listening on the VPS.

```bash
# on the VPS (needs Docker + compose plugin)
git clone <this repo> && cd meta_glasses/backend
cp .env.example .env && nano .env          # GEMINI_API_KEY, APP_TOKEN
```

**A) Nothing else uses ports 80/443 on the VPS** → let our Caddy do HTTPS:
```bash
docker compose --profile caddy up -d --build
```
Cloudflare → SSL/TLS → mode **Full (strict)**. Caddy gets a Let's Encrypt cert over HTTP-01
(Cloudflare passes `/.well-known/acme-challenge/` through). If issuance fails, use a Cloudflare
Origin Certificate instead: put the files next to the Caddyfile, mount them, and add
`tls /etc/caddy/origin.pem /etc/caddy/origin.key` to the site block.

**B) An existing nginx/Caddy already owns 80/443** → run only the API on `127.0.0.1:8000`:
```bash
docker compose up -d --build
```
and add a site to the existing proxy:
```nginx
server {
    server_name lens.projectviridis.dev;
    client_max_body_size 20m;
    location / { proxy_pass http://127.0.0.1:8000; proxy_set_header Host $host; proxy_set_header X-Forwarded-Proto $scheme; }
    # listen 443 ssl + certs as for your other sites
}
```

Check: `curl https://lens.projectviridis.dev/health` → `{"ok":true}`, then
`python smoke.py https://lens.projectviridis.dev <APP_TOKEN>`.

Update: `git pull && docker compose up -d --build` (add `--profile caddy` for A). Logs: `docker compose logs -f api`.

Limits that matter: Cloudflare free plan allows 100 MB uploads and a 100 s response timeout, both well
above what we need (5.5 MB WAV, a few seconds).

## Privacy
Stateless. Logs contain only model, token counts, latency and byte sizes, never photos, audio or text.

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
  meta:  {"locale":"cs","mode":"lecture|homework|desk","ts":"…","previous":["…"]}
         previous = steps already shown (swipe right "and what next?"), max 5

200 {"next_step":"…","card":{"title":"…","summary":"…","correction":"…","transcript_tail":"…"}}
401 bad token · 413 file too big · 422 no input · 502 both models failed (phone saves card as pending)

POST /tts    {"text":"…","voice":"Kore"}  → audio/wav
```

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

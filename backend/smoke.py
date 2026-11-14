"""Live end-to-end check against a running backend.

    python smoke.py http://localhost:8000 <APP_TOKEN> [photo.jpg] [audio.wav]

Without files it sends a generated test photo-less request with 3 s of silence,
which still proves the key, model IDs and JSON schema work.
"""

import sys
import time

import httpx

from ai import pcm_to_wav

url, token = sys.argv[1].rstrip("/"), sys.argv[2]
photo = open(sys.argv[3], "rb").read() if len(sys.argv) > 3 else None
audio = open(sys.argv[4], "rb").read() if len(sys.argv) > 4 else pcm_to_wav(b"\x00\x00" * 16000 * 3, 16000)
h = {"Authorization": f"Bearer {token}"}

files = {"audio": ("audio.wav", audio, "audio/wav")}
if photo:
    files["photo"] = ("photo.jpg", photo, "image/jpeg")

t = time.perf_counter()
r = httpx.post(f"{url}/hint", headers=h, files=files, data={"meta": '{"locale":"cs","mode":"homework"}'}, timeout=60)
print(f"/hint {r.status_code} in {time.perf_counter() - t:.2f}s\n{r.text}\n")

t = time.perf_counter()
r = httpx.post(f"{url}/tts", headers=h, json={"text": "Na třetím řádku ti vypadlo mínus."}, timeout=60)
print(f"/tts {r.status_code} in {time.perf_counter() - t:.2f}s, {len(r.content)} bytes")
if r.status_code == 200:
    open("smoke_tts.wav", "wb").write(r.content)
    print("saved smoke_tts.wav")

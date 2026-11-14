# FocusLens v2: směr buildu

> Stav k 14. 11. 2026. Vychází z [idea.md](idea.md), z rešerše oficiální dokumentace Meta, Vercelu a Upstashe a z průchodu současným kódem.
> Tvrzení označená **NEOVĚŘENO** dokumentace nepotvrzuje. Než na nich cokoli postavíme, musíme je otestovat na hardwaru (viz [Fáze 0](#fáze-0--spike-na-hardwaru-12-dny)).

## TL;DR

1. **Jeden backend, jedno UI:** Next.js na Vercelu (Hobby) a Upstash Redis (free). Python, Docker, SQLite i VPS zmizí.
2. **Brýle zobrazují jen web app.** Telefon přestane sám kreslit HUD (`Hud.kt` / DAT `sendContent`). Místo toho publikuje *view model* a web app ho vykreslí. Stejná stránka běží v brýlích (600×600) i v prohlížeči na desktopu.
3. **Telefon zůstává mozek a senzorový hub:** mic ring, foto z brýlí, IMU z brýlí přes DAT, detekce, karty. Všechna data dál leží jen na telefonu.
4. **Proaktivita přes senzory brýlí (v2 jen ty, Neural Band až ve v3):**
   - Malý ML model na telefonu rozpozná, co uživatel dělá: tabule, sešit, opisování, klid, kývnutí.
   - Stavový automat vyruší **jen když se dlouho nic neděje** (`stuck`) nebo uživatel usíná (`dozing`).
   - Přirozené pohyby hlavy nudge nikdy nespustí.
5. **Meta AI connectory (Muse) fungují pro aplikace třetích stran, včetně hlasu „Hey Meta, ask FocusLens…“** (potvrzeno vývojářem z Mety). FocusLens bude mít vlastní connector jako druhý vstup vedle pinche.

## Stav implementace (14. 11. 2026)

Kód je hotový pro všechny fáze kromě FCM. **Nic z toho zatím neběželo na hardwaru ani na Vercelu**, proto je dalším krokem Fáze 0 a deploy.

| Fáze | Stav | Kde |
|---|---|---|
| 0 spike | testovací stránka `/g/spike.html` (timery, pinch/Back, DeviceMotion Hz, latence, SSE, getUserMedia, TTS, Notification). Údery JS na pozadí jsou vidět ve Vercel logu jako `spike beat`. | `web/public/g/spike.*`, `web/app/api/spike/` |
| 1 AI routes | port `ai.py`, `/api/ai/*`, testy | `web/lib/ai.ts`, `web/ai.test.ts` |
| 2 relay + UI | Redis relay s HMAC auth (unpair = `gen:"dead"`), `/g` vykresluje HUD view model a gesta posílá jako příkaz `gesture`. Telefon: `HudView.kt`, polling místo SSE, crop přes `/api/r/img`. | `web/lib/relay.ts`, `web/public/g/app.js`, `RemoteLink.kt` |
| 3a senzory | IMU 30 Hz, features, heuristika, `AttentionMachine`, `NudgePolicy` (L1 tečka / L2), sběrový režim + export | `Imu.kt`, `Attention.kt`, `NudgePolicy.kt`, `ImuPipeline.kt`, `ImuRecorder.kt` |
| 3b model | MLP nad features běží v čistém Kotlinu (bez LiteRT), trénink přes `tools/train_activity.py` → `assets/activity_mlp.json`. Bez modelu běží heuristika. 1D-CNN přijde až s ≥ 10 h dat. | `ActivityClassifier.kt`, `tools/` |
| 4 Muse | MCP `/api/mcp` (JSON-RPC, bez SSE), OAuth 2.1 + PKCE, kde login = párovací kód, bezstavově přes HMAC. Telefon odpovídá přes `/api/r/res`. | `web/lib/mcp.ts`, `RemoteLink.kt` |
| 5 stretch | wake word přes `mwdat-speech` (`wakeEngine=dat`, default `vosk`). **FCM chybí:** potřebuje Firebase projekt (`google-services.json` + service account). | `DatWakeSpotter.kt` |

Odchylky od textu níže: hint route nezapisuje view sama (zobrazí ho telefon), z `muse:{subject}` je bezstavový token `m.id.gen.mac`, MLP místo LiteRT. `backend/` se smaže po end-to-end testu na brýlích.

**Deploy:** Vercel projekt s rootem `web/`, Upstash v `eu-central-1`, env proměnné podle `web/.env.example` (+ `RELAY_SECRET`), v telefonu `focuslens.api_url` v `local.properties`.

---

## 1. Co jsme zjistili

### 1.1 Web Apps na Meta Ray-Ban Display (oficiální dokumentace)

| Fakt | Zdroj |
|---|---|
| Hosting: jakákoli veřejná **HTTPS** URL, Vercel je výslovně uvedený | [setup](https://wearables.developer.meta.com/docs/develop/webapps/setup) |
| Instalace: `fb-viewapp://web_app_deep_link?appName=…&appUrl=…`, `navigator.install()`, QR ze Simulátoru nebo Meta AI app → App connections → *Add a Web App* | [setup](https://wearables.developer.meta.com/docs/develop/webapps/setup), [FAQ](https://developers.meta.com/wearables/faq/) |
| API: `fetch`, WebSocket, localStorage, Service Worker, DeviceMotion/DeviceOrientation (s povolením, hodnoty mohou být null), GPS z telefonu, podmnožina `speechSynthesis` (přehrává se v reproduktoru brýlí) | [build overview](https://wearables.developer.meta.com/docs/develop/webapps/build/overview/) |
| Displej 600×600, aditivní světlo (černá = průhledná) | tamtéž |
| Vstup: směrová navigace + výběr (šipky a Enter, Neural Band), volitelně pointer drag, text přes systémový composer | tamtéž, [web-apps](https://developers.meta.com/wearables/web-apps/) |
| Síť: „přes Wi-Fi brýlí nebo tethering z telefonu“. Pro stránku je to obyčejný internet | [web-apps](https://developers.meta.com/wearables/web-apps/) |
| Distribuce: URL lze chránit heslem | [blog](https://developers.meta.com/blog/build-for-display-glasses/) |

**NEOVĚŘENO (kritické pro v2):**
- běží JS (timery, fetch), když web app není v popředí nebo je displej vypnutý?
- umí se web app sama „vynořit“ (notifikace, probuzení displeje)?
- zdroj a frekvence DeviceMotion (IMU brýlí, nebo telefonu?)
- `getUserMedia` (kamera a mikrofon)
- `EventSource` (SSE)
- přístup do LAN telefonu

### 1.2 Device Access Toolkit (DAT) pro Android: to, co už používáme

Podle [meta-wearables-dat-android](https://github.com/facebook/meta-wearables-dat-android) (developer preview, `mwdat 1.0.1`):
- `mwdat-camera`: stream a foto v rámci streamu. **Samostatné foto a audio přes camera stream jsou „experimental, unavailable for production publishing“.** Na hackathon to stačí, pro publikaci ve storu je to blocker.
- `mwdat-motion`: **akcelerometr, gyroskop, magnetometr, orientace** brýlí.
- `mwdat-inputs`: navigace, tlačítko, capture, drag.
- `mwdat-display`: `sendContent()` přepíše celý displej. Displej Bluetooth renderuje z telefonu ([display overview](https://wearables.developer.meta.com/docs/develop/dat/display-overview/)).
- `mwdat-speech`: **on-device rozpoznávání řeči** (partial i final). Může nahradit Vosk wake word.
- Neural Band má vlastní senzory (IMU + EMG), oddělené od brýlí. Veřejná dokumentace DAT a Web Apps zatím popisuje jen *akce* (gesta). **Přesné API pro IMU data náramku je potřeba získat od kontaktu v Metě** (Fáze 0 #9).

### 1.3 Současný kód (co v2 mění)

- Řetězec od tapu k hintu: pinch (`Gesture.SELECT`) → `help()` ([FocusEngine.kt:578](focuslens/android/app/src/main/kotlin/com/focuslens/focuslens/focus/FocusEngine.kt#L578)) → snapshot mic ringu (dnes default 180 s, **ve v2 150 s**, [FocusConfig.kt:9](focuslens/android/app/src/main/kotlin/com/focuslens/focuslens/focus/FocusConfig.kt#L9)) → foto přes DAT → `POST /hint` → `onHintOk` → HUD, TTS a karta do `filesDir/cards/<id>/`.
- `Hud.kt` je čistá funkce stavu. Obrazovky jsou Idle, Thinking, Hint card, Quest, Blocks, BlockDetail, Message a Nudge. **Do web appky stačí přesunout renderer, engine zůstává.**
- Senzory: `mwdat-motion` jen akcelerometr, **5 Hz** ([GlassesSession.kt:289-321](focuslens/android/app/src/main/kotlin/com/focuslens/focuslens/focus/GlassesSession.kt#L289-L321)). Nudges KEY a STUCK s cooldownem 180 s ([Nudges.kt:55-59](focuslens/android/app/src/main/kotlin/com/focuslens/focuslens/focus/Nudges.kt#L55-L59)).
- Relay: `RemoteLink.kt` publikuje stav v1 a přijímá příkazy (`start_focus`, `help`, `pause_mic`, `set_config`, `inbox_*` …). Backend je drží v SQLite a v paměti a posílá je přes SSE. To na serverless nefunguje.

### 1.4 Vercel Hobby a Upstash free: limity, které nás omezují

| Limit | Hodnota | Dopad |
|---|---|---|
| Max doba funkce | 300 s | Gemini volání (10 s + fallback) v pohodě |
| **Tělo požadavku** | **4,5 MB** | Audio 150 s WAV 16 kHz (≈4,8 MB) i s fotkou neprojde → **posílat Opus/AAC (150 s ≈ 300 kB)**. Fallback na 413 (posledních 60 s) už máme. |
| Invokace | 1 M/měsíc | polling viz rozpočet níže |
| Active CPU | 4 h/měsíc | polling musí být levný (bez AI, bez velkých JSONů) |
| WebSocket | beta, max 300 s, připnutý na jednu instanci | bez pub/sub nepoužitelné, vynecháváme |
| Cron | 1× denně | nepotřebujeme |
| Překročení | žádný účet, jen ~30 dní čekání | demo nesmí spálit limit |
| Komerční použití | Hobby je pro nekomerční osobní projekty (**ověřit v Terms**) | pro hackathon OK |
| **Upstash free** | **500 k příkazů/měsíc**, 250 MB, 10 MB/request | **hlavní omezení → rozpočet příkazů** |

Zdroje: [vercel limits](https://vercel.com/docs/functions/limitations), [hobby](https://vercel.com/docs/plans/hobby), [WS](https://vercel.com/kb/guide/do-vercel-serverless-functions-support-websocket-connections), [vercel redis](https://vercel.com/docs/redis), [upstash billing](https://upstash.com/docs/redis/overall/billing).

Region: Vercel funkce i Upstash DB dát do **Frankfurtu** (`fra1` / `eu-central-1`). Default je `iad1` (USA), což přidá ~100 ms ke každému pollu.

### 1.5 Meta AI / Muse connectory

- **Connectory fungují pro aplikace třetích stran** a hlasové vyvolání „Hey Meta, ask FocusLens…“ z brýlí jde. Potvrdil to vývojář z Mety. Ve veřejné dokumentaci to zatím není.
- Platforma: *Muse Connector Platform* (`platform.muse.ai`), přijímá **API a MCP** connectory. Meta je před zveřejněním reviewuje (funkce, bezpečnost, právo). Schválený connector si uživatel zapne v Meta AI app → Settings → Connectors ([help](https://www.meta.com/help/artificial-intelligence/1066348732357744/), [parallel.ai](https://parallel.ai/articles/meta-muse-custom-integrations.md)).
- **Co si potřebujeme od Mety vyžádat:** dokumentaci k connectorům, timeout tool callu, způsob propojení účtů (OAuth) a délku review.
- **Háček:** naše data jsou jen na telefonu, server je nemá. Každý tool proto jde přes relay až na telefon a zpět. Řešení je v kapitole 3.4.

### 1.6 Senzory a ADHD: co říká výzkum

- **Usínání z IMU hlavy:** existují jen malé studie. Head-motion (MPU-6050) dosáhla 70 % ([zdroj](https://journal.uinjkt.ac.id/ti/article/view/45271)), samotný akcelerometr 80 % na 8 lidech ([zdroj](https://discovery.fiu.edu/display/pub135259)), IMU na hlavě + CNN na pozornost ([PMC9788255](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9788255/)). **Validované prahy neexistují → per-user kalibrace.**
- **Psaní ze zápěstí:** stačí 20–50 Hz ([arXiv 2206.02909](https://arxiv.org/pdf/2206.02909)) a psaní od psaní na klávesnici se odlišit dá ([ViWatch](https://www.cs.virginia.edu/~stankovic/psfiles/Mobicom_S3_ViWatch.pdf)). U ADHD je ale vysoký baseline pohybu (fidgeting), který detekci mate ([JMIR](https://formative.jmir.org/2022/4/e35803/PDF), [aktigrafie](https://ro.uow.edu.au/sspapers/2501)).
- **Nudges u ADHD:** tiché, postupně řidší cue drží výkon (1/min → 1/5 min, [core.ac.uk](https://core.ac.uk/download/268111236.pdf)). Hlasité a vizuální cue můžou rušit okolí. Únava z upozornění u ADHD studovaná není → **konzervativní default**.

---

## 2. Architektura v2

```
            ┌─────────────── brýle (Meta Ray-Ban Display) ───────────────┐
            │  Web App (Next.js /g)  ← jediné UI na displeji             │
            │   poll view (1–5 s) · pinch/šipky → příkazy · speechSynth. │
            └──────────────▲──────────────────────────┬──────────────────┘
       kamera, mic, IMU    │ HTTPS (Wi-Fi/tether)     │ HTTPS
       přes DAT (BT)       │                          ▼
┌──────────────────────────┴───┐   HTTPS   ┌──────────────────────────────────┐
│ Telefon (Flutter + Kotlin)   │──────────▶│ Vercel: Next.js                  │
│ • MicRing, foto, IMU engine  │ hint/view │  /api/ai/*   Gemini (stateless)  │
│ • FocusEngine → view model   │◀──────────│  /api/r/*    relay               │
│ • karty, inbox (jediná data) │  příkazy  │  /g          UI brýlí + desktop  │
└──────────────────────────────┘           └──────────────┬───────────────────┘
                                                          │ REST
                                               ┌──────────▼──────────┐
                                               │ Upstash Redis       │
                                               │ jen dočasná data,   │
                                               │ TTL ≤ 2 min         │
                                               └─────────────────────┘
```

### Principy
- **Telefon = zdroj pravdy.** Server nic trvale neukládá. Redis drží jen „schránku“ s TTL, jedinou trvalou hodnotou je čítač pro revokaci.
- **Web app = hloupý renderer.** Vykreslí JSON view model a uživatelův vstup pošle jako příkaz. Logika obrazovek (co kdy ukázat) zůstává ve `FocusEngine`.
- **Žádné SSE ani WebSocket.** Používáme adaptivní polling: rychlý po akci, pomalý v klidu. Je to nejlevnější spolehlivá varianta na Hobby a Upstash free.

### Tok „pinch → hint“ ve v2
1. Pinch v brýlích přijde do **web appky** (je v popředí) → `POST /api/r/cmd {type:"help"}` → `RPUSH dev:{id}:cmds`.
2. Telefon (polling během focus session) → `LPOP dev:{id}:cmds 10` → `help()`.
3. Telefon publikuje view `thinking` → web app ukáže „Looking…“.
4. Telefon: foto přes DAT + audio (Opus, ≤ 4 MB) → `POST /api/ai/hint`.
5. Route zavolá Gemini, vrátí hint telefonu **a zároveň** zapíše view `hint` do Redisu. Ušetří se tím jeden hop přes telefon.
6. Web app v rychlém pollingu (1 s po akci) hint vykreslí. Vzorec vykreslí **KaTeX v prohlížeči**, takže matplotlib zmizí.
7. Telefon uloží kartu a volitelně spustí TTS.

**Riziko:** kroky 1 a 4 předpokládají, že telefon smí fotit přes DAT, zatímco je web app v popředí, a že gesta jdou do web appky. **Ověřit ve Fázi 0.** Pokud gesta dostává DAT, krok 1–2 odpadne a je to ještě jednodušší.

### Redis schéma (Upstash, vše přes `@upstash/redis`)

| Klíč | Hodnota | TTL | Kdo |
|---|---|---|---|
| `code:{sha256(code)}` | `deviceId` | 600 s | pair `SET`, claim `GETDEL` |
| `gen:{id}` | čítač revokace | bez TTL | unpair `INCR` |
| `view:{id}` | JSON view model + stav + acky + `ts` | 120 s | telefon `SET EX`, web `MGET view gen` |
| `cmds:{id}` | list příkazů | 120 s | web `RPUSH`, telefon `LPOP n` |
| `img:{hash}` | crop JPEG (base64, ≤ 200 kB) | 1 h | hint route `SET`, web `/api/r/img/{hash}` (immutable cache) |
| `rl:{ip}:{min}` | čítač | 60 s | claim rate limit |
| `res:{cmdId}` | výsledek příkazu pro Muse tool | 60 s | telefon `SET`, `/api/mcp` long-poll `GET` |
| `muse:{subject}` | `deviceId` | bez TTL (smaže unpair) | propojení účtu Muse ↔ telefon |

**Auth bez Redisu:** `phone_secret = HMAC(SECRET, "p|"+id)` a `web_token = HMAC(SECRET, "w|"+id+"|"+gen)`. Ověření nestojí žádný příkaz. Web token se revokuje přes `gen`, které se načte ve stejném `MGET` jako view, tedy jako 1 příkaz.

### Rozpočet (1 uživatel, 3 h/den × 30 dní = 90 h)

| Provoz | Interval | Redis příkazy/měsíc | Vercel invokace |
|---|---|---|---|
| Web poll (`MGET`) | 2 s průměr (1 s po akci, 5 s v klidu) | ~162 k | ~162 k |
| Telefon poll příkazů (`LPOP`) | 2 s jen během session | ~162 k | ~162 k |
| Telefon `SET view` | při změně | ~10 k | ~10 k |
| AI volání | ~30/den | ~3 k | ~1 k |
| **Celkem** | | **~340 k / 500 k** | **~335 k / 1 M** |

→ Pro demo a jednoho uživatele to vyjde. **Pro více uživatelů:** kanál k telefonu přepnout na **FCM push** (zdarma, telefon nepolluje) a web poll prodloužit. Doplnit, až se přidá druhý tester.

---

## 3. Komponenty

### 3.1 Next.js (`web/`), nahrazuje `backend/`

```
web/
  app/
    g/page.tsx                 # UI brýlí 600×600 (+ responzivní pro desktop): renderer view modelu
    page.tsx                   # landing + pairing (?code=), install link
    api/ai/[hint|check|dump|review|tts]/route.ts
    api/r/[pair|claim|unpair|view|cmd|poll|img]/route.ts
  lib/ai.ts                    # @google/genai, prompty z ai.py
  lib/relay.ts                 # Redis + HMAC auth (port remote.py)
  lib/views.tsx                # komponenty: Idle, Thinking, Hint, Quest, Blocks, Message, Nudge
  relay.test.ts                # port test_remote.py (vitest), stejné scénáře
```

- Mapování starých endpointů: `/hint` → `/api/ai/hint` atd. Stejné multipart kontrakty, telefon změní jen `API_URL`.
- Odpadá: `formula.py` + matplotlib (nahradí KaTeX na klientu), `Dockerfile`, `compose.yaml`, `Caddyfile`, SQLite, SSE, `backend/glasses/` (přesune se do `app/g`).
- Bezpečnost portovat 1:1: whitelist příkazů, limit 64 kB na stav, sanitizace bidi znaků (test `Entropy‮`), CSP, `no-store`, `Referrer-Policy`.
- UI pro aditivní displej: černé pozadí, max 3 řádky, velký kontrast. Ovládání šipkami a Enterem musí fungovat i bez pointeru.
- `speechSynthesis` ve web appce pro krátké texty. `/api/ai/tts` zůstává pro telefon, když web app není otevřená.

### 3.2 Telefon (Kotlin + Flutter)

- **Odebrat:** volání `Hud.show()` a DAT `sendContent()` schovat za flag `hudSource = web|dat` (default `web`). `Hud.kt` smazat **až po Fázi 0**, pokud se potvrdí, že web app zvládne proaktivní zobrazení.
- **Přidat `ViewPublisher`:** serializuje stejný stav, jaký dnes dostává `Hud`, do JSON a posílá `POST /api/r/view` (debounce 300 ms).
- **`RemoteLink`:** SSE nahradit pollingem `GET /api/r/poll` (2 s během session, mimo session stop). Příkazy zůstávají stejné.
- **Audio:** `bufferSec` default zkrátit ze 180 na **150 s** ([FocusConfig.kt:9](focuslens/android/app/src/main/kotlin/com/focuslens/focuslens/focus/FocusConfig.kt#L9)). Ring kódovat do Opus/AAC (`MediaCodec`), 150 s ≈ 300 kB. Tím odpadne problém se 4,5 MB.
- **Flutter card detail:** vzorec vykreslit z LaTeX stringu (`flutter_math_fork`) místo `formula.png`.
- **Wake word:** zvážit `mwdat-speech` místo Vosku (menší APK, mikrofon brýlí). Až po stabilizaci v2.

### 3.3 Proaktivní engine (senzory)

**Rozsah v2: jen senzory brýlí.** Neural Band je odložený na v3 (kapitola 3.5).

Engine běží **na telefonu ve `FocusService`**, protože jen tam máme jistotu běhu na pozadí a data z IMU brýlí přes DAT. Web app DeviceMotion je jen záložní kandidát (viz Fáze 0).

#### Hlavní pravidlo: nerušit

Přirozené pohyby při práci jsou **produktivní, ne zaseknutí**:
- pohled na tabuli,
- pohled do sešitu,
- střídání tabule a sešitu (opisování),
- náklon hlavy,
- krátká pauza na přemýšlení,
- napití,
- otočení k sousedovi.

Výchozí reakce enginu je **nedělat nic**. Nudge přijde jen tehdy, když nějaký stav trvá dlouho a model si je jistý.

| Situace | Co vidí IMU | Stav | Nudge? |
|---|---|---|---|
| Sleduje tabuli | hlava nahoru, malé korekce | `engaged` | ne |
| Píše nebo čte v sešitu | hlava dolů, jemné mikropohyby | `engaged` | ne |
| Opisuje z tabule | pravidelné střídání nahoru a dolů | `engaged` | ne |
| Přemýšlí (≤ pár minut) | klid v jakékoli poloze | `engaged` (pauza) | ne |
| Kouká kolem, mluví se sousedem | velké otáčení hlavy do stran | `distracted` (krátce OK) | jen když trvá dlouho |
| Dlouho se nic neděje | žádná aktivita ani střídání po dobu T (výchozí 4–5 min, adaptivní), žádný pinch | **`stuck`** | **ano, tiše** |
| Mikrospánek | kývnutí (pomalý pokles pitch, rychlý návrat), propad hlavy + klid | **`dozing`** | **ano** |
| Vstal, chodí | krokový vzor v akcelerometru | `away` | ne, session se pauzne |

#### Vrstva 1: klasifikátor aktivity (ML, krátkodobý)

- **Vstup:** `mwdat-motion` akcelerometr + gyro + orientace, **≥ 25 Hz**. Dnešních 5 Hz na mikropohyby nestačí (ověřit ve Fázi 0 #5, včetně spotřeby baterie).
- **Kontext zdarma z telefonu:** hlasitost a aktivita řeči z mic ringu, který už běží (mluví přednášející?), čas od posledního pinche nebo hintu a mód (lecture/homework/desk). **Kamera se průběžně nepoužívá** (baterie, soukromí).
- **Okno:** 4 s s krokem 2 s.
- **Třídy:** `board`, `notes`, `switching`, `still`, `looking_around`, `nod`, `walking`.
- **Model:**
  1. **Start, s málo daty:** ručně spočítané features + malý MLP nebo gradient boosting. Features jsou střední hodnota a rozptyl pitch/roll, energie úhlové rychlosti yaw, energie v pásmech 0,5–3 Hz a 3–10 Hz, počet přechodů mezi polohou „tabule“ a „sešit“ a histogram pitch. Model má desítky kB, je vysvětlitelný a natrénuje se z několika hodin dat.
  2. **Až bude ≥ 10 h označených dat:** malá 1D-CNN nebo GRU nad surovým IMU (9 kanálů × 100 vzorků), ~20–50 k parametrů, int8 **LiteRT (TFLite) ~50–100 kB**. Inference trvá < 1 ms každé 2 s, takže spotřeba je zanedbatelná.
- **Personalizace:** první 2 minuty session se automaticky najdou dvě dominantní polohy hlavy (k-means s k = 2 na pitch), tedy „tabule“ a „sešit“ pro dané místo k sezení. Features se pak normalizují vůči nim. Pevné úhly by v každé lavici selhaly.
- **Bootstrap:** než bude model, stejné rozhraní implementuje heuristika (prahy na stejných features). Pipeline tak funguje od prvního dne a heuristika zároveň slabě labeluje data pro trénink.

#### Vrstva 2: stavový automat (pravidla, dlouhodobý)

- Agreguje posloupnost tříd z vrstvy 1 za minuty a určí stav: `engaged`, `distracted`, `stuck`, `dozing` nebo `away`.
- **Rozhodnutí vyrušit je průhledné pravidlo, ne černá skříňka.** Díky tomu se dá ladit a vysvětlit („nudge přišel, protože 5 min žádná aktivita“).
- Hystereze: stav se změní, až když nový stav drží ≥ 30 s, a nudge vyžaduje průměrnou pravděpodobnost ≥ 0,8 za celé okno.
- `stuck`:
  - **homework/desk:** po dobu T chybí `notes` i `switching`, nepřišel pinch a uživatel není `away`.
  - **lecture:** dlouhé `still` nebo `looking_around`, zatímco přednášející mluví.
- `dozing`: ≥ 2 `nod` za 3 min, nebo propad hlavy následovaný klidem > 20 s.

#### Data pro trénink

- **Sběrový režim v appce:** nahrává IMU okna a labely do `filesDir/imu/`. Data neopustí telefon, dokud je uživatel sám neexportuje.
- **Zdroje labelů:**
  1. **Skriptované sezení týmu**, ~15 min na osobu: 5 min opisování z tabule, 5 min psaní, 3 min přemýšlení, 2 min koukání kolem, simulovaná kývnutí, chůze.
  2. **Zpětná vazba na nudge:** „← ne“ znamená falešný poplach, „pinch“ znamená trefu. To jsou nejcennější labely, protože pochází z reálného použití.
- Trénink probíhá na notebooku (Python → LiteRT) a model se přibalí do `assets/`. Retrain je ruční, bez online učení.

#### Politika vyrušení (proti únavě z upozornění)

- **Eskalace:**
  - **L0:** nic.
  - **L1:** malá tečka v rohu HUD, bez zvuku a jen když web app svítí.
  - **L2:** krátký text „Zaseknutý? Pinch = další krok“, pokud stav trvá další 2 min.
- **Zvuk (TTS)** jen u `dozing` a jen pokud ho uživatel zapnul. Reproduktory brýlí jsou otevřené a okolí je slyší.
- **Rozpočet:** max 1 nudge za 10 min a max 4 za hodinu (dnešní cooldown 180 s se zpřísní).
- **Tiché periody:**
  - prvních 5 min session (kalibrace),
  - 3 min po pinchi nebo hintu,
  - celý stav `away`.
- **Zpětná vazba:** každé „← ne“ prodlouží T o 20 % a uloží label. Třikrát „ne“ po sobě vypne daný typ nudge do konce session.

### 3.4 Meta AI connector („Hey Meta, ask FocusLens…“)
- **MCP endpoint v Next.js** (`/api/mcp`). Tooly:
  - `help`: „what did I miss?“, spustí stejný `help()` jako pinch.
  - `start_focus` / `stop_focus`
  - `add_note`: hlasová poznámka do inboxu
  - `last_hint`
- **Tok:** Muse zavolá tool → server udělá `RPUSH cmds:{id}` → telefon příkaz vyzvedne a provede → výsledek zapíše do `res:{cmdId}` (TTL 60 s). Server mezitím čeká long-pollem (`GET res:{cmdId}` každých 500 ms, max ~15 s, podle timeoutu Muse) a vrátí text, který Meta AI přečte.
  - Cena: ~30 Redis příkazů na jedno volání. Při pár voláních denně je to zanedbatelné.
- **Propojení účtů:** v OAuth flow connectoru uživatel zadá párovací kód z telefonu → `muse:{subject}` → `deviceId`. Je to jediný další trvalý klíč v Redisu.
- Během long-pollu je potřeba zrychlit polling telefonu (1 s). Alternativa je FCM push z Fáze 5.
- Integrace Todoist a Notion z telefonu zůstávají beze změny.

### 3.5 Později (v3): senzory Neural Bandu
Náramek má vlastní IMU, oddělené od brýlí, takže by šlo detekovat psaní přímo ze zápěstí (energie v pásmu 2–10 Hz, 50 Hz). Fidgeting u ADHD je tam ještě silnější rušivý vliv. Pokud kontakt v Metě poskytne API, data se přidají jako další vstup do klasifikátoru z 3.3 a architektura se nemění.

---

## 4. Plán

### Fáze 0: spike na hardwaru (1–2 dny)
Žádný produkční kód, jen testovací stránka na Vercelu a debug build telefonu. Odpovědi rozhodnou o několika větvích plánu:

| # | Otázka | Pokud ne → |
|---|---|---|
| 1 | Běží JS, timery a fetch web appky, když displej spí nebo je v popředí jiná app? | proaktivní vizuál přes DAT display (`hudSource=dat` pro nudge), web jen pro interaktivní UI |
| 2 | Kam jde pinch, když je web app v popředí: do web appky, nebo i do DAT inputs? | pokud do DAT, pinch řeší telefon přímo (rychlejší) |
| 3 | Funguje DAT foto a audio stream, když je web app v popředí? | foto spouštět jinak, jinak je to **blocker architektury** |
| 4 | DeviceMotion ve web appce: IMU brýlí? Jaká frekvence? | detekce zůstane na telefonu (plán A) |
| 5 | Max frekvence `mwdat-motion` a dostupnost gyra | nod detekce jen z akcelerometru (pomalejší) |
| 6 | Dá se DAT display a web app zobrazit současně? Co vyhraje? | rozhodne o flagu `hudSource` |
| 7 | Latence fetch z web appky (Wi-Fi vs tether) | upravit polling intervaly |
| 8 | `EventSource` ve web appce | jen dokumentovat (polling stejně používáme) |
| 9 | (pro v3) API a frekvence IMU Neural Bandu od kontaktu v Metě | zůstává jen IMU brýlí |
| 10 | Dokumentace Muse connectorů: timeout toolu, OAuth, review | upravit long-poll a propojení účtů (3.4) |

### Fáze 1: Next.js + AI routes (2–3 dny)
Port `ai.py` → `lib/ai.ts`, routes `/api/ai/*`, deploy do `fra1`. Telefon přepnout na nový `API_URL`. Hotovo, když hint z brýlí projde end-to-end přes Vercel.

### Fáze 2: relay + UI brýlí (3–4 dny)
Redis schéma, HMAC auth, `/api/r/*`, port `test_remote.py` do `relay.test.ts`. `/g` renderuje všechny obrazovky z `Hud.kt`. Na telefonu `ViewPublisher` a polling místo SSE. Hotovo, když celý focus flow (start, hint, quest, nudge, blocks) běží v brýlích jen přes web app a `backend/` jde smazat.

### Fáze 3a: senzorová pipeline + sběr dat (3–4 dny)
- IMU brýlí: gyro a ≥ 25 Hz.
- Výpočet features a heuristický klasifikátor.
- Stavový automat, politika vyrušení a sběrový režim.
- Skriptovaná sezení týmu (cíl ≥ 3 h dat).

### Fáze 3b: model (3–5 dní)
- Features + MLP/GBT → LiteRT, vyměnit za heuristiku za stejným rozhraním.
- 1D-CNN až po ≥ 10 h dat.

**Hotovo (obě fáze), když na skriptovaném testu:**
- **30 min normální práce** (tabule, sešit, opisování, pauzy na přemýšlení do 2 min) dá **0 nudge**,
- **„nic se neděje“** (sezení nad sešitem bez aktivity) dá nudge do T + 30 s,
- **simulované kývnutí** dá `dozing` do 10 s,
- **přesnost klasifikátoru** je na held-out osobě ≥ 80 %. Nejdůležitější metrika je ale **falešné nudge za hodinu ≤ 0,5**.

### Fáze 4: Muse connector (2–3 dny + review od Mety)
`/api/mcp`, tooly z kapitoly 3.4, propojení účtů a odeslání do review. Hotovo, když „Hey Meta, ask FocusLens what did I miss“ vrátí hint hlasem do 15 s.

### Fáze 5: stretch
FCM push místo pollingu telefonu, `mwdat-speech` wake word.

---

## 5. Rizika

| Riziko | Dopad | Mitigace |
|---|---|---|
| Web app se neprobudí sama (proaktivita) | nudge nejsou vidět | Fáze 0 #1 → fallback DAT display jen pro nudge + TTS |
| DAT foto a audio jsou „experimental, not publishable“ | nelze dát do storu | na hackathon OK, sledovat DAT release notes |
| Upstash 500 k / Vercel 4 h CPU | služba se zastaví až na 30 dní | adaptivní polling, polling jen během session, FCM ve Fázi 4 |
| Polling přidá 1–2 s latence | hint působí pomaleji | rychlý poll 1 s po akci, view `thinking` hned po pinchi |
| Falešné nudge (ADHD fidgeting) | uživatel app vypne | kalibrace per user, přísný rozpočet, „← ne“ zvedá práh |
| Málo trénovacích dat | model se plete mezi „přemýšlí“ a „zasekl se“ | rozhoduje časové pravidlo (T), ne model; sběr labelů z „← ne“ |
| 25 Hz stream z IMU vybíjí baterii brýlí | kratší výdrž | Fáze 0 #5 změří spotřebu, fallback 12,5 Hz + jen orientace |
| Review Muse connectoru trvá | hlasový vstup nebude na demo | odeslat hned po Fázi 2, pinch funguje i bez něj |
| Hobby = nekomerční | při launchi nutný Pro | ověřit Vercel Terms před veřejným spuštěním |

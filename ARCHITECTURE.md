# FocusLens: architektura

Produktová idea je v [idea.md](idea.md), práce s Meta SDK v [META_WEARABLES_SETUP.md](META_WEARABLES_SETUP.md).

**Jedna věta:** telefon v kapse drží **180 s audio** v kruhovém bufferu. Uživatel udělá **gesto na Meta Neural Band** (náramek na zápěstí), brýle pořídí **jednu fotku**, obojí jde na backend, AI vrátí **jeden konkrétní další krok** na HUD brýlí a na pozadí se uloží **kontextová karta**.

**Cílové zařízení:** **Meta Ray-Ban Display + Meta Neural Band**. Brýle nemají ťuknutí na rám, ovládají se náramkem (EMG gesta), takže tím ovládá i naši aplikaci. Ostatní Meta brýle (bez displeje a náramku) jsou jen fallback s TTS výstupem.

---

## 1. Přehled komponent

```
┌─────────────────────┐
│ NEURAL BAND (ruka)  │  pinch = Select, swipe = Nav
└─────────┬───────────┘
          ▼
┌─────────────────────┐        ┌──────────────────────────────────────────────┐        ┌──────────────────────────┐
│ META RAY-BAN DISPLAY│  BT    │ TELEFON – Flutter app „FocusLens“            │ HTTPS  │ BACKEND (1 endpoint)     │
│                     │◄──────►│                                              │◄──────►│                          │
│ • InputEvent ───────┼──event─┼─► TriggerService                             │        │ POST /hint               │
│ • kamera (spí) ◄────┼─photo──┼── GlassesBridge (Kotlin/Swift ↔ DAT 1.0.x)   │        │  1. STT (180 s → text)   │
│ • HUD 600×600 ◄─────┼─text───┼── OutputService ─┬─ HUD (Display)            │        │  2. Claude (foto + text) │
│ • repro (A2DP) ◄────┼─audio──┼──────────────────┴─ TTS (ostatní brýle)      │        │  3. JSON {next_step,card}│
│                     │        │                                              │        │                          │
│                     │        │ AudioRingBuffer 180 s (RAM, foreground svc)  │        │ API klíče jen tady       │
│                     │        │ CardStore (lokální soubory)                  │        └──────────────────────────┘
└─────────────────────┘        └──────────────────────────────────────────────┘
```

| Komponenta | Kde | Odpovědnost |
|---|---|---|
| `GlassesBridge` | Flutter plugin v repu (`android/` Kotlin, `ios/` Swift) | registrace, camera + display session, `capturePhoto()`, `showText()`, stream `InputEvent` (Select / Nav / Capture) |
| `AudioRingBuffer` | Dart | posledních 180 s PCM v RAM, `snapshot()` |
| `TriggerService` | Dart | převede gesta z Neural Band (a fallbacky) na akce `help` / `prev` / `next` / `dismiss` |
| `HintPipeline` | Dart | foto + audio paralelně → backend → výstup + karta |
| `OutputService` | Dart | HUD, pokud jsou brýle Display, jinak TTS přes A2DP |
| `CardStore` | Dart | ukládá karty na disk, seznam pro večerní review |
| Backend `/hint` | serverless funkce | STT + Claude, drží API klíče |

---

## 2. Hlavní flow: gesto → nápověda

```
t=0      pinch na Neural Band ──► InputEvent.Select ──► TriggerService (help)
         HUD okamžitě: „⏳ Dívám se…“  (zpětná vazba, že gesto prošlo)
         ├─► AudioRingBuffer.snapshot()        (≈ 0 ms, kopie 5,76 MB)
         └─► GlassesBridge.capturePhoto()      (start session + foto, ⚠️ změřit)
t≈1 s    HintPipeline: POST /hint  (multipart: photo.jpg + audio.wav + meta)
         backend: STT(audio) ─► Claude(photo, transcript) ─► {next_step, card}
t≈3–4 s  OutputService.show(next_step)  → HUD / TTS
         CardStore.save(photo, audio, card)   (na pozadí)
```

**Cíl: < 4 s od gesta k nápovědě.** Hlavní neznámé jsou start camera session (proto změřit v sample appce) a upload audia (viz §3.4).

Chybové stavy (vždy dát uživateli zpětnou vazbu, nikdy mlčet):
- brýle nepřipojené / foto selže → pošli jen audio a přidej `"photo": null`
- backend nedostupný → krátké pípnutí / text „Uloženo, zkusím později“ a karta se uloží jako `pending` (retry při dalším spuštění)
- `.batteryLow` → HUD hláška, zůstane jen audio režim

---

## 3. Audio ring buffer (180 s)

### 3.1 Parametry

| Parametr | Hodnota | Proč |
|---|---|---|
| Délka | **180 s** | pokryje celý „výpadek“ i kontext výkladu před ním. Prompt přesto zdůrazní posledních ~60 s |
| Formát | PCM 16‑bit, mono, **16 kHz** | standard pro STT, víc nepřinese přesnost |
| Velikost | 180 × 16 000 × 2 B = **5 760 000 B ≈ 5,5 MB** | v RAM bez problému |
| Úložiště | **jen RAM**, nikdy disk (dokud uživatel neťukne) | soukromí okolí, nic se neukládá „pro jistotu“ |

### 3.2 Zdroj zvuku

| Zdroj | Kdy | Pozn. |
|---|---|---|
| **Mikrofon telefonu** ✅ MVP default | telefon na lavici / v kapse | žádné BT routování. V kapse horší kvalita, ale pro řeč přednášejícího v místnosti obvykle stačí |
| Mikrofon brýlí (HFP 8 kHz) | volitelné nastavení | trvale otevřený SCO kanál žere baterii brýlí a degraduje A2DP výstup, 8 kHz zhorší STT. Zapnout jen když se ukáže, že telefon v kapse nestačí |

### 3.3 Implementace (Dart)

Balíčky: `record` (PCM stream 16 kHz), Android `flutter_foreground_task` (foreground service typu `microphone`, bez ní Android nahrávání v pozadí zabije), iOS `UIBackgroundModes: audio`.

```dart
// lib/audio/ring_buffer.dart
import 'dart:typed_data';

/// Kruhový buffer PCM16 mono. Drží posledních [seconds] sekund.
class AudioRingBuffer {
  AudioRingBuffer({this.seconds = 180, this.sampleRate = 16000})
      : _buf = Uint8List(seconds * sampleRate * 2);

  final int seconds, sampleRate;
  final Uint8List _buf;
  int _write = 0;
  bool _full = false;

  void add(Uint8List chunk) {
    // ponytail: max. dvě setRange kopie na chunk, žádné zámky (Dart isolate je jednovláknový)
    final src = chunk.length > _buf.length ? chunk.sublist(chunk.length - _buf.length) : chunk;
    final first = (_buf.length - _write).clamp(0, src.length);
    _buf.setRange(_write, _write + first, src);
    _buf.setRange(0, src.length - first, src, first);
    if (_write + src.length >= _buf.length) _full = true;
    _write = (_write + src.length) % _buf.length;
  }

  /// Chronologická kopie (nejstarší → nejnovější).
  Uint8List snapshot() => _full
      ? (BytesBuilder(copy: false)..add(_buf.sublist(_write))..add(_buf.sublist(0, _write))).takeBytes()
      : _buf.sublist(0, _write);
}
```

```dart
// test/ring_buffer_test.dart
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:focuslens/audio/ring_buffer.dart';

void main() {
  test('drží jen posledních N bajtů ve správném pořadí', () {
    final rb = AudioRingBuffer(seconds: 1, sampleRate: 2); // kapacita 4 B
    rb.add(Uint8List.fromList([1, 2, 3]));
    expect(rb.snapshot(), [1, 2, 3]);
    rb.add(Uint8List.fromList([4, 5, 6]));
    expect(rb.snapshot(), [3, 4, 5, 6]);
    rb.add(Uint8List.fromList([7, 8, 9, 10, 11]));       // větší než kapacita
    expect(rb.snapshot(), [8, 9, 10, 11]);
  });
}
```

Napojení:
```dart
final rb = AudioRingBuffer();
final stream = await AudioRecorder().startStream(
  const RecordConfig(encoder: AudioEncoder.pcm16bits, sampleRate: 16000, numChannels: 1),
);
stream.listen(rb.add);
```

Na ťuknutí: `rb.snapshot()` → přidat 44B WAV hlavičku → upload.

### 3.4 Známý strop: velikost uploadu
5,5 MB WAV je na Wi‑Fi ~1 s, na slabém mobilním signálu i 5–10 s.
`// ponytail: posíláme raw WAV. Upgrade: zakódovat snapshot do Opus/AAC (~24 kbps → ~0,5 MB), nebo průběžná on-device STT a v bufferu držet text místo audia.`
Řešit až po změření na reálné síti.

---

## 4. Ovládání: Meta Neural Band

Brýle se ovládají náramkem Neural Band (EMG gesta prstů). DAT modul `mwdat-inputs` (`Inputs` capability) posílá aplikaci sémantické události `InputEvent`: `Select`, `Nav`, `Button`, `Capture`, `Drag`. Podle dokumentace se **navigace a výběr z Neural Band (i z touchpadu na brýlích) doručí jako `InputEvent.Nav` / `InputEvent.Select`**.
Zdroj: [Android inputs API](https://wearables.developer.meta.com/llms.txt?product=all&version=1.0&document=products/dat/sections/android-api/modules/com.meta.wearable.dat.inputs)

### Mapování gest (celá aplikace = 4 gesta)

| Gesto (Neural Band) | `InputEvent` | Akce ve FocusLens |
|---|---|---|
| **pinch (výběr)** | `Select` | **„Pomoc“**: foto + audio → nápověda. Když nápověda svítí, dalším pinchem ji zavřeš |
| swipe vpravo | `Nav` (right) | „A co dál?“: další krok ke stejné situaci (backend dostane i předchozí odpověď) |
| swipe vlevo | `Nav` (left) | zpět na předchozí nápovědu (posledních 5 drží telefon v paměti) |
| capture tlačítko na brýlích | `Capture` | záložní spouštěč „Pomoc“ (⚠️ ověřit konflikt s nativním focením do galerie Meta AI) |

`TriggerService` převede `InputEvent` na akce `help | next | prev | dismiss` a přidá debounce 1,5 s, protože EMG může gesto zachytit dvakrát.

### Display session = „FocusLens běží“
- Display session musí **uživatel explicitně spustit**, a pak má aplikace HUD exkluzivně. Proto UX: na začátku přednášky / učení uživatel v aplikaci (nebo gestem) spustí **„Focus režim“**. Tím se zapne display session i audio buffer.
- **Idle obrazovka:** jedna malá ikona / tečka v rohu („poslouchám, pinch = pomoc“). Žádný jiný obsah, ať nic neruší.
- Display po neaktivitě **ztlumí a usne**. To je pro idle stav žádoucí, jen musí platit, že gesto aplikaci dorazí i v uspaném stavu.
- Systém může session přerušit (hovor, notifikace). `GlassesBridge` sleduje stav session a po přerušení ji obnoví, nebo na telefonu ukáže „Focus režim přerušen, obnovit?“.

### Varianta B: HUD tlačítko
Pokud se ukáže, že syrové `InputEvent.Select` mimo UI nechodí, idle obrazovka bude jedna display komponenta **`Button` „Pomoc“** s tap handlerem. SDK posílá interakce s UI komponentami zpět do aplikace jako události a pinch na Neural Band button aktivuje. Pro uživatele je to stejné gesto.

### Fallbacky (demo a brýle bez náramku)
Velké tlačítko v aplikaci na telefonu, Bluetooth media tlačítko a tlačítko hlasitosti.

### ✅ Ověřeno na hardwaru (2026‑11‑14, Gesture Lab, Meta RB Display, Meta AI 298, Developer Mode)
- Gesta Neural Band **chodí do 3rd‑party app** i bez schválení ve Wearables Developer Center (Developer Mode, app id `0`).
- **Pinch** → `InputEvent.Select(source=NEURAL_BAND)`. Před každým Select přijde krátká série `Drag DOWN → MOVE… → UP` (`source=NEURAL_BAND_DRAG`, posuny ~0,005). Drag proto ignorovat, nebo brát jen velké posuny.
- **Swipe** → `InputEvent.Nav(direction=LEFT|RIGHT|UP|DOWN, source=NEURAL_BAND)`.
- HUD tlačítko „Pomoc“ nedostalo žádný klik, pinch šel jako syrové `Select`. Varianta B tedy není potřeba.
- Latence: `session.start()` → Inputs `ACTIVE` ≈ 0,7 s, Display `STARTED` ≈ 0,6 s.
- Session ukončená uživatelem přímo na brýlích přijde jako chyba **„Session ended by device“** + `STOPPED`. Aplikace to musí brát jako „uživatel ukončil Focus režim“, ne jako pád, a session sama znovu nespouštět.

### Zbývá ověřit
1. Chodí gesta při uspaném displeji?
2. Auto-obnova session po přerušení systémem (hovor, notifikace), abychom ji odlišili od ukončení uživatelem.
3. Vydrží display session 90 min přednášky (baterie, systémové timeouty)?
4. Která gesta si rezervuje systém (např. otevření menu Meta)?

---

## 5. Kamera

**✅ Ověřeno na hardwaru (2026‑11‑14):**
- **Kamera nesmí vznikat a zanikat za běhu displeje.** `addCamera`/`removeCamera` při aktivním HUD vedly k chybě `Failed to render display content` a pak k pádu session. Proto pořadí jako v Meta BirdSpotter: session `STARTED` → `addCamera` (`VideoQuality.LOW`, 7 fps, `compressVideo = true`) → čekat na `STREAMING` (≈ 1,5–2,2 s) → teprve pak `addDisplay`. **Stream běží celou Focus session.** Idea, že „kamera spí“, se tím mění na „kamera streamuje v nejnižší kvalitě“. Spotřebu baterie změřit při testu 90 min.
- `stream.capturePhoto()` vrací **1440×1080 JPEG (~120–150 kB) za 1,1–2,0 s**, a to i s LOW streamem. Uložení do JPEG ~0,1 s.
- Komprimovaný stream (`compressVideo = true`) SDK nepauzuje, když appka odejde z popředí. Raw stream by se pauzoval.
- Session vždy přes `SpecificDeviceSelector(id připojených brýlí)`. `AutoDeviceSelector` se plní asynchronně a hned po vytvoření hlásí `No eligible device`.
- `DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED` (typicky po přeinstalaci appky) → `Wearables.openDATGlassesAppUpdate(activity)`.
- Samostatné `Camera.photo` (vyšší kvalita, experimentální) zatím nepoužíváme.

---

## 6. Backend `/hint`

Jeden endpoint, serverless (Cloud Run / Cloudflare Worker / Vercel…). Proč backend a ne volat AI přímo z appky: **API klíč nesmí být v aplikaci** (dá se z APK vytáhnout).

### Kontrakt
```
POST /hint   (multipart/form-data, Authorization: Bearer <app token>)
  photo:  image/jpeg            (volitelné)
  audio:  audio/wav             (180 s, 16 kHz mono)
  meta:   {"locale":"cs","mode":"lecture|homework|desk","ts":"…"}

200 OK
{
  "next_step": "Na třetím řádku ti vypadlo mínus. Oprav ho a vynásob rovnici dvěma.",
  "card": {
    "title": "Kvadratická rovnice – chyba ve znaménku",
    "summary": "…",
    "correction": "…",
    "transcript_tail": "…posledních ~60 s přepisu…"
  }
}
```

### Kroky na backendu
1. **STT**: přepis 180 s audia s časovými značkami. Claude audio vstup nebere, takže je potřeba samostatná STT služba (Whisper / Deepgram / Google STT, rozhodnout podle ceny, latence a češtiny).
2. **Claude**: jeden request (foto + přepis), **structured output** (`output_config.format` s JSON schema `{next_step, card}`).
   - Model: `claude-opus-5-5` s `output_config.effort: "low"` (rychlost, jednoduchý úkol). Pokud bude latence problém, je k dispozici i levnější a rychlejší `claude-haiku-5-5`, ale to je produktové rozhodnutí, ne default.
   - Zapnuté server-side **fallbacks** (`betas: ["server-side-fallback-2026-07-01"]`, `fallbacks: "default"`), aby odmítnutí klasifikátorem neskončilo prázdnou odpovědí.
   - Vždy kontrolovat `stop_reason` před čtením obsahu.
3. Vrátit JSON. Nic neukládat (stateless), karta žije jen v telefonu.

### Jádro promptu (system)
```
Jsi exekutivní kopilot pro člověka s ADHD. Dostaneš fotku toho, na co se právě dívá,
a přepis posledních 180 s zvuku (nejdůležitějších je posledních ~60 s).
Vrať JEDEN konkrétní další krok: max. 20 slov, rozkazovací způsob, žádný úvod.
Neprozrazuj celé řešení, jen nejbližší krok. Pokud je na fotce chyba, pojmenuj
přesně kde. Pokud uživatel „vypadl“ z výkladu, řekni, co si má zapsat / co právě zaznělo.
```

---

## 7. Výstup

| Brýle | Výstup |
|---|---|
| **Meta Ray-Ban Display** (primární) | `GlassesBridge.showText(next_step)`: jedna `Text` komponenta, velké písmo, dole drobná nápověda gest („→ dál · pinch zavřít“). Zmizí po 20 s nebo pinchem. Každé `sendContent()`/`send()` nahradí celou obrazovku, takže stav se drží v Dartu a posílá se vždy celý |
| ostatní (fallback) | `flutter_tts` na telefonu, zvuk jde přes A2DP do brýlí. Rychlost řeči o něco pomalejší |

Volitelně i na Display: krátké TTS současně s textem, jako nastavení „čti nahlas“.

Žádné dlouhé texty, žádné odstavce. Tohle je hlavní produktové pravidlo z idea.md.

---

## 8. Kontextové karty (asynchronní záchranná síť)

Úložiště: adresář aplikace, jedna složka na kartu. Bez databáze, dokud nepotřebujeme hledání.

```
<app documents>/cards/2026-11-14T10-32-05/
  photo.jpg
  audio.wav        # celých 180 s (nebo jen posledních 60 s, nastavitelné)
  card.json        # odpověď backendu + status: done | pending
```

Flutter obrazovka „Dnes“: seznam karet (`Directory.list()` seřazený podle názvu), detail s fotkou, přehrávačem a textem. Mazání karty = smazání složky.
`// ponytail: soubory místo sqflite. Přidat DB, až bude potřeba fulltext nebo sync mezi zařízeními.`

---

## 9. Struktura Flutter projektu

```
focuslens/
  lib/
    main.dart
    glasses/glasses.dart          # Dart API nad MethodChannel/EventChannel
    audio/ring_buffer.dart
    audio/recorder.dart           # record + foreground service
    trigger.dart                  # TriggerService
    pipeline.dart                 # HintPipeline + OutputService
    cards.dart                    # CardStore + UI seznamu
    ui/                           # home (stav brýlí, buffer běží), cards, settings
  android/app/src/main/kotlin/…/GlassesPlugin.kt   # DAT Android
  ios/Runner/GlassesPlugin.swift                    # DAT iOS
  test/ring_buffer_test.dart
backend/
  hint.(py|ts)                    # jeden soubor, jeden endpoint
```

Závislosti (Flutter): `record`, `flutter_foreground_task`, `flutter_tts`, `http`, `path_provider`, `just_audio` (přehrání karty). Nic dalšího, dokud to není potřeba.

---

## 10. Soukromí a bezpečnost

- Audio buffer **jen v RAM**, přepisuje se, na disk jde až po vědomém ťuknutí.
- Viditelná indikace: trvalá notifikace foreground služby („FocusLens poslouchá posledních 3 min“) + rychlý vypínač. Na brýlích svítí LED při focení.
- Nahrávání okolí (spolužáci, přednášející) má právní rozměr (GDPR, souhlas). Do onboardingu dát upozornění, režim „pauza“ jedním tapem a respektovat zákazy nahrávání.
- Komunikace app ↔ backend přes HTTPS + token. Na hackathon stačí sdílený token, pro produkt per‑user auth.
- Backend nic neloguje kromě metadat (latence, chyby), žádné fotky ani přepisy.

---

## 11. Rizika a otevřené otázky

| # | Riziko | Dopad | Jak ověřit / mitigace |
|---|---|---|---|
| 1 | Gesta Neural Band nechodí do 3rd‑party app (jen systém) | ruší core UX | spike s `mwdat-inputs`. Varianta B (HUD `Button`), fallback tlačítko v appce |
| 2 | ~~Pomalý start kamery~~ | ✅ vyřešeno: kamera streamuje celou session, foto 1,1–2,0 s | zbývá změřit baterii za 90 min |
| 3 | Cílíme na Ray-Ban Display + Neural Band (dražší, menší trh) | méně uživatelů | ostatní brýle podporovat přes TTS a tlačítko v appce |
| 3b | Display session se během 90 min přeruší nebo usne | gesto nedorazí | sledovat stav session, automaticky obnovit. Test 90 min |
| 4 | Background mikrofon (zvlášť iOS) | buffer přestane běžet | foreground service / audio background mode. Test s uzamčeným telefonem 60 min |
| 5 | DAT preview: max 100 testerů, nelze publikovat | žádný veřejný release | pro hackathon / pilot OK. Sledovat changelog |
| 6 | Upload 5,5 MB na slabé síti | latence | změřit. Komprese (§3.4) |
| 7 | Kvalita STT v češtině z kapsy | špatná nápověda | porovnat 2 STT služby na nahrávce z reálné přednášky |

---

## 12. Milníky

1. **Spike (1–2 dny):** CameraAccess sample + mock → foto z brýlí. Logování všech `InputEvent` z Neural Band (Select / Nav) s aktivní i neaktivní display session. Změřená latence.
2. **MVP (hackathon):** Flutter app, ring buffer 180 s, pinch na Neural Band jako trigger (fallback tlačítko v appce), backend `/hint`, výstup na HUD, karty.
3. **Plné ovládání:** swipe dál/zpět, obnova přerušené session, teplá camera session.
4. **Polishing:** komprese audia, režimy (přednáška / úkol / stůl), onboarding a soukromí.

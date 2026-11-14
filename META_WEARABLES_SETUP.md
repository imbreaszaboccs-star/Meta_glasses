# Meta Wearables: jak začít vyvíjet pro Meta brýle

Praktický návod pro **Meta Wearables Device Access Toolkit (DAT)** – oficiální SDK, přes které mobilní aplikace (Android / iOS) přistupuje ke kameře, mikrofonům, reproduktorům, displeji a vstupům Meta brýlí.

> Stav k 2026‑11‑14. DAT se rychle mění – před implementací vždy ověř verze na stránce [Version dependencies](https://wearables.developer.meta.com/docs/develop/dat/version-dependencies). Body označené ⚠️ nejsou v dokumentaci jednoznačně potvrzené.

---

## 1. Co to je a jak to funguje

```
┌──────────────┐  Bluetooth Classic   ┌──────────────────────┐   internet   ┌─────────┐
│ Meta brýle   │◄────────────────────►│ Telefon              │◄────────────►│ Backend │
│ kamera, mic, │                      │  ├─ Meta AI app      │              │ / AI    │
│ repro, HUD,  │                      │  │  (párování, auth) │              └─────────┘
│ tlačítka     │                      │  └─ TVOJE APP + DAT  │
└──────────────┘                      └──────────────────────┘
```

- **Aplikace neběží na brýlích.** Běží na telefonu a brýle používá jako periferii (kamera / audio / displej / vstupy).
- **Meta AI app** (dříve Meta View) na telefonu drží spojení s brýlemi. Tvoje aplikace se přes ni registruje a uživatel jí udělí oprávnění.
- Data tečou přes **Bluetooth Classic**, takže šířka pásma je omezená (viz limity kamery níže).

**Aktuální verze:** SDK **1.0.1** (vyžaduje Meta AI app **V292**, firmware brýlí **V128**). DAT 1.0 vyšlo 24. 9. 2026, ale README a dokumentace pořád mluví o *developer preview*. Počítej s tím, že publikace do obchodů zatím není plně otevřená (viz §8).

### Podporované brýle
Ray-Ban Meta (Gen 1 / Gen 2), Ray-Ban Meta Optics, Meta glasses, **Meta Ray-Ban Display** (jediné s HUD), Oakley Meta HSTN, Oakley Meta Vanguard.
Zdroj: [Getting started](https://wearables.developer.meta.com/docs/develop/dat/getting-started-toolkit)

### SDK moduly

| Modul (Android artefakt / iOS modul) | K čemu |
|---|---|
| `mwdat-core` / `MWDATCore` | inicializace, registrace, zařízení, session, oprávnění |
| `mwdat-camera` / `MWDATCamera` | video stream + `capturePhoto()` |
| `mwdat-display` | vykreslování na HUD (jen Meta Ray-Ban Display) |
| `mwdat-inputs` | události z brýlí: navigace, tlačítko, capture, drag |
| `mwdat-motion` | pohyb hlavy |
| `mwdat-speech` | řeč ⚠️ (detaily nejsou zdokumentované, prozkoumat) |
| `mwdat-mockdevice` | Mock Device Kit: vývoj bez fyzických brýlí |

---

## 2. Co nainstalovat

| Nástroj | Verze | Poznámka |
|---|---|---|
| **Flutter SDK** | stable | pro naši aplikaci (DAT samo Flutter nepodporuje, viz §7) |
| **Android Studio** | Flamingo+ | |
| **Kotlin** | 2.2+ | |
| Android telefon | **Android 10+** | fyzický telefon, Bluetooth |
| **Xcode** | 26.4+ | **jen na macOS**. Z Windows iOS buildy nepostavíš (Mac nebo CI s macOS runnerem) |
| iPhone | **iOS 17.2+** | |
| **Meta AI app** | V292+ | na telefonu, spárovaná s brýlemi |
| Firmware brýlí | V128+ | aktualizuje se přes Meta AI app |
| Claude Code plugin (volitelný) | – | `claude plugin marketplace add meta/meta-wearables-dat-android` |

---

## 3. Účet a projekt (jednorázově)

1. Vytvoř si účet a organizaci ve **Wearables Developer Center** (https://wearables.developer.meta.com/).
2. Zaregistruj **projekt** → dostaneš `APPLICATION_ID` a `CLIENT_TOKEN`. Viz [Manage projects](https://wearables.developer.meta.com/docs/develop/dat/manage-projects/).
3. Nastav **release channels**, tedy testery (max. **100 testerů**). Viz [Release channels](https://wearables.developer.meta.com/docs/develop/dat/set-up-release-channels).

⚠️ Přesný průběh registrace účtu se v docs nepodařilo plně ověřit, takže se řiď průvodcem v Developer Center.

### Developer Mode (pro lokální vývoj, bez schváleného projektu)
1. Meta AI app → **Settings → App Info** → 5× ťukni na **App version**.
2. Zapni **Developer Mode** → **Enable**.
3. Tvoje aplikace se pak objeví v Meta AI app pod **Settings → App connections → Developer mode apps**.
4. V Developer Mode stačí `CLIENT_TOKEN = "0"`.

---

## 4. Android integrace

Zdroj: [Build integration – Android](https://wearables.developer.meta.com/docs/develop/dat/build-integration-android/), [GitHub](https://github.com/facebook/meta-wearables-dat-android)

### Gradle (Maven Central, bez tokenu)
```toml
# gradle/libs.versions.toml
[versions]
mwdat = "1.0.1"

[libraries]
mwdat-core       = { group = "com.meta.wearable", name = "mwdat-core",       version.ref = "mwdat" }
mwdat-camera     = { group = "com.meta.wearable", name = "mwdat-camera",     version.ref = "mwdat" }
mwdat-display    = { group = "com.meta.wearable", name = "mwdat-display",    version.ref = "mwdat" }
mwdat-inputs     = { group = "com.meta.wearable", name = "mwdat-inputs",     version.ref = "mwdat" }
mwdat-mockdevice = { group = "com.meta.wearable", name = "mwdat-mockdevice", version.ref = "mwdat" }
```
```kotlin
// settings.gradle.kts → repositories { google(); mavenCentral() }
// app/build.gradle.kts
dependencies {
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.display)
    implementation(libs.mwdat.inputs)
    debugImplementation(libs.mwdat.mockdevice)
}
```

### AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.INTERNET" />
<!-- CAMERA jen pro mock (kamera telefonu místo brýlí) -->

<application ...>
    <meta-data android:name="com.meta.wearable.mwdat.APPLICATION_ID" android:value="TVOJE_APP_ID" />
    <meta-data android:name="com.meta.wearable.mwdat.CLIENT_TOKEN"   android:value="0" /> <!-- 0 = Developer Mode -->
</application>
```
⚠️ `minSdk` / `compileSdk` nejsou v docs uvedené. Převezmi je ze sample appky **CameraAccess** v GitHub repu.

### Základní flow (orientačně, přesné signatury ověř v sample appce)
```kotlin
Wearables.initialize(context)                 // 1× za proces (Application.onCreate)

Wearables.startRegistration(activity)         // otevře Meta AI app → uživatel povolí
Wearables.registrationState                   // Flow se stavem registrace
Wearables.devices                             // Flow s dostupnými brýlemi

// oprávnění ke kameře brýlí: Permission.CAMERA (check + request)

// session se zařízením
val session = createSession(AutoDeviceSelector())   // nebo SpecificDeviceSelector(id)
session.start()                                     // stavy STARTED / STOPPED

// kamera: stream + foto
// rozlišení: high 720×1280 | medium 504×896 (default) | low 360×640
// fps: 2 | 7 | 15 | 24 | 30 (default 30), kodek jen VideoCodec.raw
val photo = stream.capturePhoto()                   // JPEG
```

---

## 5. iOS integrace

Zdroj: [Build integration – iOS](https://wearables.developer.meta.com/docs/develop/dat/build-integration-ios/), [GitHub](https://github.com/facebook/meta-wearables-dat-ios)

- **Swift Package:** `https://github.com/meta/meta-wearables-dat-ios` ⚠️ (README uvádí `facebook/meta-wearables-dat-ios`, zkus obě). Moduly `MWDATCore`, `MWDATCamera`.
- **Info.plist:**
  - dict `MWDAT` s klíči `AppLinkURLScheme`, `MetaAppID`, `ClientToken`, `TeamID`
  - `UIBackgroundModes` = `bluetooth-central` (pro náš audio buffer navíc `audio`)
  - `NSBluetoothAlwaysUsageDescription`, `NSLocalNetworkUsageDescription`, `NSBonjourServices`
  - `NSCameraUsageDescription` (jen mock), `NSMicrophoneUsageDescription` (náš buffer)
  - `CFBundleURLTypes`: URL scheme pro návrat z Meta AI app (nutné v Developer Mode)
- **Entitlements:** Associated Domains, HotspotConfiguration.

```swift
try await Wearables.shared.startRegistration()
// v AppDelegate / onOpenURL:
Wearables.shared.handleUrl(url)

let status = try await checkPermissionStatus(.camera)
try await requestPermission(.camera)

let photo = try await stream.capturePhoto(format: .jpeg)
```

⚠️ Publikace do App Store podle iOS guide zatím **není podporovaná**. Distribuce jde jen přes TestFlight / dev buildy.

---

## 6. Vývoj bez brýlí: Mock Device Kit

- Artefakt `mwdat-mockdevice` (`MockDeviceKitConfig`).
- Místo brýlí použije **kameru telefonu** (proto `CAMERA` permission).
- Nejjednodušší cesta: debug UI v sample appce **CameraAccess**. Programové mock API není zdokumentované.
- Zdroj: [Mock Device Kit](https://wearables.developer.meta.com/docs/develop/dat/mock-device-kit/)

---

## 7. Flutter: jak DAT napojit

**Oficiální Flutter SDK neexistuje.** Máme tyto možnosti:

| Možnost | Pro | Proti |
|---|---|---|
| **A) Vlastní tenký plugin** (MethodChannel / EventChannel → Kotlin + Swift) ✅ doporučeno | vždy aktuální DAT 1.0.x, voláme jen ~6 metod | nativní kód 2× |
| B) Komunitní `meta_wearables_dat_flutter` 0.7.1 (iSee Labs) | rychlý start | obaluje **DAT 0.7.0**, neoficiální |
| C) `flutter_meta_wearables_dat` 0.7.1, `meta_wearables_dat` 0.1.3 | – | stejný problém, malá komunita |

Doporučení: na první spike vyzkoušej (B), ať rychle uvidíš foto z brýlí ve Flutteru. Pro produkt piš (A). API, které z Dartu potřebujeme:

```dart
abstract final class Glasses {
  static const _m = MethodChannel('focuslens/glasses');
  static const _events = EventChannel('focuslens/glasses/events'); // tap, stav zařízení

  static Future<void> register() => _m.invokeMethod('register');
  static Future<void> startSession() => _m.invokeMethod('startSession');
  static Future<Uint8List> capturePhoto() async => (await _m.invokeMethod<Uint8List>('capturePhoto'))!;
  static Future<void> showText(String text) => _m.invokeMethod('showText', text); // HUD
  static Stream<String> get events => _events.receiveBroadcastStream().cast<String>();
}
```

---

## 8. Schopnosti a limity (důležité pro FocusLens)

| Oblast | Stav |
|---|---|
| **Foto** | `capturePhoto()` během stream session. Samostatné `Camera.photo` (od 1.0.0) je **experimentální**. Rozlišení fotky, latence a chování LED nejsou zdokumentované ⚠️ |
| **Video stream** | max 720×1280 @ 30 fps. Při slabém BT toolkit sníží nejdřív rozlišení, pak fps (nikdy pod 15) |
| **Mikrofon brýlí** | přes Bluetooth **HFP, 8 kHz mono** ⚠️. Synchronizované PCM audio ve camera streamu je **experimentální a nepublikovatelné** |
| **Reproduktory** | Bluetooth **A2DP**, brýle se chovají jako sluchátka, takže běžné TTS z telefonu funguje |
| **HUD (Display)** | jen **Meta Ray-Ban Display**, 600×600, komponenty FlexBox / Text / Image / Button / Icon / Video. Každé odeslání nahradí celou obrazovku, během session má app exkluzivní kontrolu, systém ji může přerušit (hovor, notifikace) |
| **Vstupy** | `mwdat-inputs` (`Inputs` capability) posílá `InputEvent`: `Button`, `Nav`, `Select`, `Capture`, `Drag`. **Navigace a výběr z Meta Neural Band (náramek) i z touchpadu přichází jako `InputEvent.Nav` / `InputEvent.Select`**. Capture tlačítko jako `InputEvent.Capture`. iOS: `MWDATInputs` (`InputEvent`, `NavDirection`, `InputSource`, `InputsState`…). Interakce s display komponentami (`Button`) chodí do appky jako události. ⚠️ Neověřeno: jestli gesta chodí i bez aktivní display session a jestli si část nerezervuje systém ([discussion #95](https://github.com/facebook/meta-wearables-dat-android/discussions/95)) |
| **Baterie** | existuje chyba `.batteryLow`, jinak čísla nejsou |
| **Distribuce** | max 100 testerů, publikace pro glasses „coming soon“, experimentální funkce nelze publikovat |
| **Nezjištěno** | background běh, nutnost foreground appky, regionální omezení |

---

## 9. Doporučený postup krok za krokem

1. Nainstaluj Meta AI app, spáruj brýle, aktualizuj firmware, zapni **Developer Mode**.
2. Naklonuj `facebook/meta-wearables-dat-android`, spusť sample **CameraAccess** a ověř stream + foto (nejdřív s mockem, pak s brýlemi).
3. V sample appce zaloguj všechny `InputEvent` z `mwdat-inputs` a zjisti, co přijde po gestech na **Neural Band** (pinch, swipe) a po stisku capture tlačítka, a to s aktivní i neaktivní display session.
4. Změř latenci: `session.start()` → `capturePhoto()` → JPEG v ruce.
5. Teprve pak vytvoř Flutter projekt s vlastním pluginem (§7). Architektura je v [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Zdroje
- https://wearables.developer.meta.com/
- https://wearables.developer.meta.com/docs/develop/dat/getting-started-toolkit
- https://wearables.developer.meta.com/docs/develop/dat/version-dependencies
- https://wearables.developer.meta.com/docs/develop/dat/build-integration-android/
- https://wearables.developer.meta.com/docs/develop/dat/build-integration-ios/
- https://wearables.developer.meta.com/docs/develop/dat/streaming
- https://wearables.developer.meta.com/docs/develop/dat/display-overview/
- https://wearables.developer.meta.com/docs/develop/dat/mock-device-kit/
- https://wearables.developer.meta.com/docs/develop/dat/microphones-and-speakers/
- https://github.com/facebook/meta-wearables-dat-android · https://github.com/facebook/meta-wearables-dat-ios
- https://developers.meta.com/blog/meta-connect-recap/ · https://developers.meta.com/blog/build-for-display-glasses/
- https://pub.dev/packages/meta_wearables_dat_flutter

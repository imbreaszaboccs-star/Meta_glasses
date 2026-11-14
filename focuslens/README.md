# FocusLens (Flutter, Android)

Aktuálně **Gesture Lab**: loguje všechny události z Meta Ray-Ban Display + Neural Band (`InputEvent` z DAT `mwdat-inputs`) a kliky na HUD tlačítko.

- Dart UI: [lib/main.dart](lib/main.dart)
- Most na Meta DAT 1.0.1: [android/app/src/main/kotlin/com/focuslens/focuslens/GlassesBridge.kt](android/app/src/main/kotlin/com/focuslens/focuslens/GlassesBridge.kt)

## Spuštění
1. Telefon: Meta AI app, spárované brýle, **Developer Mode** zapnutý (viz [../META_WEARABLES_SETUP.md](../META_WEARABLES_SETUP.md) §3).
2. Telefon: zapnuté **USB ladění**, připojit kabelem.
3. `flutter run` (ve složce `focuslens/`).
4. V appce: **1. Registrovat** → potvrdit v Meta AI app → **2. Start session** → dělat gesta.
5. Log: tlačítko kopírovat, nebo `adb logcat -s FocusLens`.

## Experiment
| Přepínače | Co testuje |
|---|---|
| HUD zap + tlačítko zap | gesta + jestli pinch klikne na HUD tlačítko (`[hud]`) nebo přijde jako `SELECT` |
| HUD zap + tlačítko vyp | gesta s aktivním displejem bez UI prvku |
| HUD vyp | gesta bez display session |

`addInputs: PERMISSION_DENIED` = Inputs není povolené pro appku ve Wearables Developer Center (experimentální capability).

## Hlasové spuštění

"Hey Meta, start FocusLens" vyžaduje schválení **Voice Invocations** a nastavené mluvené jméno appky ve Wearables Developer Center; experimentální, jen dev/beta.

## Wake word "hey lens"

Offline na telefonu (Vosk, Apache-2.0), poslouchá stejné mikrofonní PCM jako buffer. Model (~40 MB) není v gitu, stáhni ho jednou:

```
powershell -File tool\get_wake_model.ps1
```

Bez modelu appka jen jednou zaloguje `wake word off` a běží dál bez wake wordu. Zapnutí/vypnutí: Nastavení → Wake word "hey lens".

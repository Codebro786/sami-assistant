# SAMI — Technical Plan

## 1. Environment (inspected 2026-11-23)
| Item | Found |
|---|---|
| Device | OnePlus 12 (CPH2573, OP595DL1), **Android 16 / API 36**, OxygenOS 16.0.10.601, arm64-v8a |
| Current assistant role holder | Google (`GsaVoiceInteractionService`) |
| JDK | Android Studio JBR **OpenJDK 25.0.4** (system Java is 8 — not usable) |
| Gradle | **9.9.2** (wrapper) — Gradle 8.11 in cache can't run on JDK 25 |
| AGP / Kotlin | **AGP 9.5.2**, Kotlin Compose plugin **2.4.21** (AGP 9 built-in Kotlin) |
| SDK | Platforms 35, 36, 37.0, **37.2** installed; build-tools 35/36 |
| App config | `compileSdk 37.2` (required by Compose BOM 2026.11.01), `targetSdk 36` (= phone), `minSdk 29` |

## 2. What Android allows on a non-rooted OnePlus 12

| Capability | Feasible? | Mechanism |
|---|---|---|
| Open apps, Home, settings screens | ✅ Direct | Intents + `<queries>` |
| Media volume / ring / alarm | ✅ Direct (ring blocked in DND unless DND access) | `AudioManager` |
| Play/pause/next | ✅ For apps with media sessions | `AudioManager.dispatchMediaKeyEvent`; richer control via `MediaSessionManager` needs notification-listener access (M4) |
| Flashlight | ✅ Direct | `CameraManager.setTorchMode` (fails if camera in use) |
| Wi-Fi / Bluetooth toggle | ⚠️ Not directly since Android 10/13 | Settings panel / settings screen; user taps |
| Brightness | ⚠️ Needs `WRITE_SETTINGS` special access | Settings.System, user grants in Settings (M2) |
| Back, Recents, notification shade, quick settings, screenshot, tap/scroll/swipe | ⚠️ Only via user-enabled **AccessibilityService** | `performGlobalAction`, node actions, `dispatchGesture` (M2) |
| Become default assistant (long-press power/gesture) | ✅ With user choice | `VoiceInteractionService` + `RoleManager.ROLE_ASSISTANT` (M2); M1 handles `ACTION_ASSIST` |
| Always-on wake word via **system** hotword (`AlwaysOnHotwordDetector`, `HotwordDetectionService`, DSP) | ❌ Privileged — needs `MANAGE_HOTWORD_DETECTION` / preinstalled system app | Not possible for a sideloaded app |
| Always-on wake word in an app | ⚠️ Possible with a **foreground service of type `microphone`**, persistent notification + green mic indicator | Android 14+ forbids *starting* mic FGS from background; must start while app visible (or from tile/boot exemptions) and keep running. Works on lock screen while running. OxygenOS battery optimisation must be set to "Unrestricted". |
| Battery saver, DND | ⚠️ DND with notification-policy access; battery saver only opens settings | |
| Force-stop other apps, read UPI PIN/OTP, approve payments | ❌ Never (impossible and/or prohibited by design) | |

## 3. Wake-word engine evaluation
| Engine | Licence | Custom "Hey Sami" | Notes | Verdict |
|---|---|---|---|---|
| **Picovoice Porcupine** | Free tier for personal/non-commercial; AccessKey required; commercial needs paid plan | Yes — train in Picovoice Console (minutes) | Tiny CPU/battery, very accurate, Android SDK on Maven | **Primary choice** (user supplies their own AccessKey + `.ppn`) |
| openWakeWord (ONNX/TFLite) | Apache 2.0 (models CC BY-NC-SA for pre-trained) | Yes, train via synthetic TTS pipeline (needs Python/GPU) | Heavier than Porcupine, fully free | Fallback / fully-open option |
| Vosk grammar spotting | Apache 2.0 | Any phrase, no training | ~40 MB model, higher CPU — worse battery | Not recommended for always-on |
| Sensory / Snowboy | Commercial / discontinued | — | — | Rejected |

Plan: `WakeWordEngine` interface, `PorcupineEngine` implementation, configurable sensitivity, 2-stage false-trigger guard (Porcupine detection → SpeechRecognizer confirms command speech). If no AccessKey configured, wake word is disabled and fallbacks are used: assist gesture, Quick Settings tile, app shortcut, notification action.

## 4. Architecture
```
ui/ (Compose: SamiRoot, orb/, history, settings)   ← MVVM: AssistantViewModel (StateFlow)
core/ (AppContainer DI, conversation orchestrator, OrbState)
voice/ (SpeechInput = SpeechRecognizer flow, SpeechOutput = TTS, wake/ M2)
commands/ (Command sealed model, CommandParser = Path A, en/hi/Hinglish)
ai/ (M3: LlmProvider interface → JSON action plan → schema validation → Command)
actions/ (ActionRegistry: id, description, permissions, risk, validate, execute → ActionResult)
apps/ (AppRepository, AppMatcher)
system/ (M2: VoiceInteractionService, AccessibilityService, QS tile, mic FGS)
payments/ (M5: UPI intent builder + confirmation card; never touches PIN/OTP)
```
Rules: AI output is data only (validated JSON → `Command`); no shell/code execution; every `ActionResult` reflects the real platform return value; HIGH-risk actions (calls, messages, payments) always go through a CONFIRMATION card.

**API keys:** never shipped in the APK. Personal use: key typed into Settings and stored with Android Keystore-encrypted storage. Production: a small backend proxy (e.g. Cloudflare Worker) holds the provider key and authenticates the device.

## 5. Milestones
See the brief. M1 is implemented; status in `FEATURE_STATUS.md`.

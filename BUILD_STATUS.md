# Build Status

| Date | Milestone | Result |
|---|---|---|
| 2026-11-23 | M1 | `assembleDebug` ✅ · `testDebugUnitTest` ✅ 14/14 |

## Build
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
Requirements: JDK 17+ (Android Studio JBR 25 works), SDK platform `android-37.2` (Compose BOM 2026.11.01 requires compileSdk 37.1+).

## Known build notes
- System `java` on PATH is Java 8; always set `JAVA_HOME` to the Android Studio JBR.
- `targetSdk` is 36 to match the phone's Android 16.
- 2026-11-23: installed on the OnePlus 12; volume, flashlight, Hinglish commands and app launch verified via logcat.
- 2026-11-23 M2: build ✅, 16/16 tests ✅, installed (OxygenOS asks to confirm each USB install on the phone).
- 2026-11-23 M2.1: Vosk wake engine, arm64-only APK (82 MB, model 36 MB), 21/21 tests ✅, wake→command verified on device.
- 2026-11-23 M2.2: Siri-style overlay; shared AssistantController (no ViewModel); 21/21 tests ✅.
- 2026-11-23 M3: Groq brain, personal actions, confirmations + biometrics, wake energy gate; 29/29 tests ✅; installed.

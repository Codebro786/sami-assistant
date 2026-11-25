# Sami — a personal voice assistant for Android

> © 2026 Codebro786. **All rights reserved.** Source shown for reference only — no copying, reuse, redistribution or
> re-publishing without written permission. See [LICENSE](LICENSE).

Sami is a voice-first assistant for Android, written in Kotlin with Jetpack Compose. Say **"Hey Sami"** and a Siri-style overlay
appears over whatever you're doing; ask in English, Hindi or Hinglish and it acts on the phone.

Built and tested on a OnePlus 12 (Android 16 / OxygenOS 16).

## What it can do
- **Wake word "Hey Sami"**, fully on-device (Vosk, energy-gated for ~1.5% of one CPU core when idle). Optional Picovoice Porcupine.
- **Fast local commands** (no AI, offline): open apps, volume, brightness, flashlight, media, Do Not Disturb, alarms, timers,
  reminders, screen control (back, scroll, tap by name), screenshots, battery, screen time — with typo correction and Hinglish.
- **AI brain** (Groq, your own free key): understands everything else, plans multi-step actions as validated JSON (never runs
  generated code), learns from corrections ("no, I meant …") and caches what worked so it runs offline next time.
- **Web research**: searches the web and Reddit, reads the top pages and gives a short verdict with sources.
- **People**: nicknames for contacts, direct calls, WhatsApp messages (you confirm), message and Gmail summaries (read-only).
- **UPI payment preparation**: "pay Rahul 200" → confirmation card with editable amount → opens Paytm with the UPI ID filled in.
  You always enter the PIN in Paytm; Sami never touches it and never claims a payment succeeded.
- **Files & photos**: find documents by name or by reading their text (on-device OCR), private "My documents", hand-picked photos.
- **Class reminders** from a weekly timetable, with room and floor (edit `system/Timetable.kt` for your own schedule).
- **Settings by voice**: "talk faster", "turn off wake word", "set sensitivity to 70", "reply in English".
- **Backup/restore** of all app data to a PC (`tools/backup-sami.ps1`, `tools/restore-sami.ps1`).

## Build
Requirements: Android Studio (its bundled JBR), Android SDK platform 37.2.
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
Create `local.properties` with your `sdk.dir` (it is git-ignored).

## Setup on the phone
1. Open Sami and grant the microphone. Set Sami as **Default digital assistant app** (Settings › Apps › Default apps).
2. Settings › AI brain: paste a free Groq API key (console.groq.com). Keys are stored encrypted with the Android Keystore.
3. Optional: screen control (Accessibility), notification access, contacts, photos, all-files access — each explained in-app.
4. Gmail (optional): see [docs/GMAIL_SETUP.md](docs/GMAIL_SETUP.md). Wake word: [docs/WAKE_WORD_SETUP.md](docs/WAKE_WORD_SETUP.md).

## Android 16 / OxygenOS notes learned the hard way
- Background apps' audio is muted ("AudioHardening"), so Sami renders TTS to a file and plays it itself.
- UPI/banking apps refuse to run while any third-party accessibility service is on — Sami switches its own off inside them.
  Turning it back on automatically needs `WRITE_SECURE_SETTINGS`, which OxygenOS 16 won't grant; use the system
  accessibility shortcut (hold both volume keys) instead.
- OxygenOS stretches ordinary exact alarms by up to an hour; class reminders use `setAlarmClock`.

## Privacy
No audio leaves the phone until you speak a command. Documents, faces and photos stay on the device. Notification and screen
text are sent to the AI only when you ask and only if you allow it in Settings.

## Third-party components
See [NOTICE.md](NOTICE.md).

## License
© 2026 Codebro786. All rights reserved — see [LICENSE](LICENSE). This is not open-source software.

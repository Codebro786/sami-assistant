# Wake word setup

**No account needed.** Since v0.2.0 Sami uses Vosk (open source, Apache 2.0) with a bundled
Indian-English model. Just open Sami › Settings › turn on **"Hey Sami" detection**.
Battery must be Unrestricted and Sami should be the Default digital assistant app.

Picovoice is now **optional** (lower battery use). The steps below are only for that.


If you add both a Picovoice AccessKey and a "Hey Sami" .ppn file, Sami uses Porcupine instead of Vosk.

## 1. Create a free account and copy your AccessKey
1. On your PC open **https://console.picovoice.ai** and sign up (Google/GitHub sign-in works).
   Choose the **Free** plan (personal / non-commercial use).
2. On the console home page you'll see **AccessKey** — click **Copy**.
3. Keep it private: it's tied to your account. Sami stores it encrypted on the phone only.

## 2. Train the "Hey Sami" keyword
1. In the console, open **Porcupine** (Wake Word) in the left menu.
2. Language: **English**. Type the phrase **`Hey Sami`** and press Enter / Train.
   (You can test it with your PC mic in the browser.)
3. Click **Download**, choose platform **Android**, accept, and download.
   You'll get a `.zip`; extract it — inside is a file like `Hey-Sami_en_android_v4_0_0.ppn`.
   The version must be **v4** to match Sami's Porcupine 4.0.2 library.
4. Put the `.ppn` file on the phone (Google Drive, WhatsApp-to-self, USB copy to Downloads, or
   `adb push Hey-Sami_en_android_v4_0_0.ppn /sdcard/Download/`).

Optional: train a second keyword **`Sami`** alone. Single short words trigger falsely more often,
so "Hey Sami" is recommended. (Sami currently loads one keyword file.)

## 3. Add them in Sami
1. Open Sami › **Settings** › *Wake word*.
2. Paste the AccessKey › **Save key**.
3. **Choose .ppn file** › pick the file from Downloads. You should see "✓ keyword".
4. Turn on **"Hey Sami" detection**. Status should read **Listening for "Hey Sami"**, and a
   "Sami" notification with a mic indicator appears.

## 4. Make it survive in the background (OxygenOS)
- Settings › Permissions › **Unrestricted battery** › Allow.
- Also: long-press Sami icon › App info › **Battery usage** › enable *Allow background activity*.
- Set Sami as **Default digital assistant app** so a wake word can pop Sami over other apps and the lock screen.

## Troubleshooting
| Status text | Fix |
|---|---|
| Needs a Picovoice AccessKey | Step 3.2 |
| Needs your "Hey Sami" .ppn keyword file | Step 3.3 |
| Porcupine rejected the key or keyword | Key copied wrong, keyword trained for wrong platform/version, or free-tier device limit reached (check console) |
| Paused by Android — open Sami to resume | Android stopped the service; opening Sami restarts it |
| Wakes too often / too rarely | Adjust the Sensitivity slider (0.5–0.7 is typical) |

Free-tier terms change over time; check the console for current limits (monthly active devices etc.).

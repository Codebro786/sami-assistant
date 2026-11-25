# Connect your Gmail to Sami (one-time, ~10 minutes, free)

Sami reads Gmail **read-only** with your own Google sign-in. Google requires every app that
reads Gmail to have its own "OAuth client", so you create one for Sami in your Google account.

**Values you'll need (copy exactly):**
- Package name: `com.sami.assistant`
- SHA-1 fingerprint of your signing key — get it with:
  `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android`
  (this is the debug signing key on your PC; if you ever build a release APK with a different key, add that key's SHA-1 too)

## Steps (on your PC)
1. Open **https://console.cloud.google.com** and sign in (your personal Gmail is fine).
2. Top bar › project picker › **New project** › name it `Sami` › Create, then select it.
3. Left menu › **APIs & Services › Library** › search **Gmail API** › **Enable**.
4. Left menu › **Google Auth Platform** (or "OAuth consent screen") › **Get started**:
   - App name: `Sami`, User support email: your email › Next
   - Audience: **External** › Next
   - Contact email: your email › Next › agree › **Create**
5. **Audience** tab › **Test users** › **Add users** › add your **college email** (and any other Gmail you want Sami to read) › Save.
   (The app stays in "Testing" — that's fine for personal use.)
6. **Clients** tab › **Create client** › Application type **Android**:
   - Name: `Sami Android`
   - Package name: `com.sami.assistant`
   - SHA-1: paste your SHA-1 › **Create**
7. On the phone: Sami › Settings › AI brain › **Gmail › Connect** › pick your college account › **Allow**.
   Google will warn "Google hasn't verified this app" — tap **Continue** (it's your own app).

## Then try
- "Do I have any important mail?" / "koi important mail aaya?"
- "Any mail from placement cell?" / "mail about exam schedule"

## If it fails
| Message | Fix |
|---|---|
| "Access blocked: … admin" | Your college Google Workspace blocks third-party apps. Ask IT, or forward college mail to a personal Gmail and connect that. |
| "Developer error" / code 10 | Package name or SHA-1 in step 6 doesn't match — re-check both. |
| "Access denied" | The account isn't in Test users (step 5). |

package com.sami.assistant.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Nickname → exact contact name. Empty by default; add your own in Settings › Nicknames (e.g. "mom" = "Mom Mobile"). */
val DEFAULT_ALIASES = linkedMapOf<String, String>()

/** Saved UPI IDs by exact contact name (in addition to ones learned per contact). */
val DEFAULT_PAYEES = emptyMap<String, String>()

/** User preferences. Secrets (API keys) are AES-GCM encrypted with a hardware-backed Keystore key. */
class SamiPrefs(private val context: Context) {
    private val p = context.getSharedPreferences("sami", Context.MODE_PRIVATE)

    var wakeEnabled: Boolean get() = p.getBoolean("wake_enabled", false); set(v) = p.edit().putBoolean("wake_enabled", v).apply()
    var sensitivity: Float get() = p.getFloat("wake_sensitivity", 0.6f); set(v) = p.edit().putFloat("wake_sensitivity", v).apply()
    var ttsRate: Float get() = p.getFloat("tts_rate", 1.05f); set(v) = p.edit().putFloat("tts_rate", v).apply()
    var ttsPitch: Float get() = p.getFloat("tts_pitch", 1.0f); set(v) = p.edit().putFloat("tts_pitch", v).apply()
    var continuous: Boolean get() = p.getBoolean("continuous", true); set(v) = p.edit().putBoolean("continuous", v).apply()

    /** Custom "Hey Sami" Porcupine keyword (.ppn), copied into private storage. */
    val keywordFile: File get() = File(context.filesDir, "wake/hey_sami.ppn")
    val hasKeyword get() = keywordFile.isFile && keywordFile.length() > 0

    var picovoiceKey: String?
        get() = p.getString("pv_key", null)?.let { runCatching { decrypt(it) }.getOrNull() }
        set(v) { if (v.isNullOrBlank()) p.edit().remove("pv_key").apply() else p.edit().putString("pv_key", encrypt(v.trim())).apply() }

    // ---- AI ---------------------------------------------------------------------------------
    var groqKey: String?
        get() = p.getString("groq_key", null)?.let { runCatching { decrypt(it) }.getOrNull() }
        set(v) { if (v.isNullOrBlank()) p.edit().remove("groq_key").apply() else p.edit().putString("groq_key", encrypt(v.trim())).apply() }
    var groqModel: String get() = p.getString("groq_model", null) ?: "openai/gpt-oss-120b"; set(v) = p.edit().putString("groq_model", v.trim()).apply()
    /** Explicit consent before notification text is sent to the AI for summaries. */
    var aiNotifications: Boolean get() = p.getBoolean("ai_notif", true); set(v) = p.edit().putBoolean("ai_notif", v).apply()
    var aiScreen: Boolean get() = p.getBoolean("ai_screen", true); set(v) = p.edit().putBoolean("ai_screen", v).apply()
    var wakeTime: String? get() = p.getString("wake_time", "07:00"); set(v) = p.edit().putString("wake_time", v).apply()
    var pauseWakeOnLowBattery: Boolean get() = p.getBoolean("wake_low_batt", true); set(v) = p.edit().putBoolean("wake_low_batt", v).apply()

    // ---- Memory (facts) and UPI payees, both encrypted -------------------------------------
    var facts: List<String>
        get() = secretJson("facts")?.let { a -> org.json.JSONArray(a).let { arr -> (0 until arr.length()).map { arr.getString(it) } } } ?: emptyList()
        set(v) = putSecret("facts", org.json.JSONArray(v.takeLast(50)).toString())
    /** contact key → UPI ID */
    var payees: Map<String, String>
        get() = DEFAULT_PAYEES + (secretJson("payees")?.let { s -> org.json.JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } ?: emptyMap())
        set(v) = putSecret("payees", org.json.JSONObject(v).toString())

    /** nickname → exact contact display name. Seeded with the user's people on first run. */
    var aliases: Map<String, String>
        get() = secretJson("aliases")?.let { s -> org.json.JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } ?: DEFAULT_ALIASES
        set(v) = putSecret("aliases", org.json.JSONObject(v).toString())
    /** What the user meant: normalised utterance → corrected utterance ("learns from mistakes"). */
    var learned: Map<String, String>
        get() = secretJson("learned")?.let { s -> org.json.JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } ?: emptyMap()
        set(v) = putSecret("learned", org.json.JSONObject(v.entries.toList().takeLast(200).associate { it.key to it.value }).toString())
    /** Successful AI plans reused offline: normalised utterance → plan JSON. */
    var planCache: Map<String, String>
        get() = secretJson("plans")?.let { s -> org.json.JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } ?: emptyMap()
        set(v) = putSecret("plans", org.json.JSONObject(v.entries.toList().takeLast(300).associate { it.key to it.value }).toString())
    var gmailConnected: Boolean get() = p.getBoolean("gmail", false); set(v) = p.edit().putBoolean("gmail", v).apply()
    /** "auto" (match the user), "english" or "hinglish". */
    var replyLanguage: String get() = p.getString("reply_lang", "auto")!!; set(v) = p.edit().putString("reply_lang", v).apply()
    var sleepPause: Boolean get() = p.getBoolean("sleep_pause", true); set(v) = p.edit().putBoolean("sleep_pause", v).apply()
    var sleepTime: String get() = p.getString("sleep_time", null) ?: "00:00"; set(v) = p.edit().putString("sleep_time", v).apply()

    private fun secretJson(k: String) = p.getString(k, null)?.let { runCatching { decrypt(it) }.getOrNull() }
    private fun putSecret(k: String, v: String) = p.edit().putString(k, encrypt(v)).apply()

    // ---- Keystore ---------------------------------------------------------------------------
    private val alias = "sami_secrets"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }
    private fun decrypt(enc: String): String {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
        return String(c.doFinal(raw, 12, raw.size - 12))
    }
}

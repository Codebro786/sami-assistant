package com.sami.assistant.system

import android.content.Context
import android.util.Log
import com.sami.assistant.core.SamiPrefs
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup/restore of Sami's data so an uninstall/reinstall loses nothing.
 * Secrets are encrypted with an Android Keystore key that is destroyed on uninstall, so the backup stores them
 * decrypted inside the zip. The zip is copied to the owner's PC (Documents\Sami-Backup) over adb — treat it as private.
 *
 * Export: files/backup/sami-backup.zip   Restore: put a zip at files/restore/sami-backup.zip → imported on next app start.
 */
object BackupManager {
    private val secretKeys = listOf("groq_key", "pv_key", "facts", "payees", "aliases", "learned", "plans")

    fun export(context: Context): File {
        val prefs = SamiPrefs(context)
        val sp = context.getSharedPreferences("sami", Context.MODE_PRIVATE)
        val plain = JSONObject()
        sp.all.forEach { (k, v) -> if (k !in secretKeys) plain.put(k, JSONObject().put("t", v?.javaClass?.simpleName).put("v", v)) }
        val secrets = JSONObject()
            .put("groq_key", prefs.groqKey).put("pv_key", prefs.picovoiceKey)
            .put("facts", org.json.JSONArray(prefs.facts)).put("payees", JSONObject(prefs.payees))
            .put("aliases", JSONObject(prefs.aliases)).put("learned", JSONObject(prefs.learned)).put("plans", JSONObject(prefs.planCache))
        val out = File(context.filesDir, "backup").apply { mkdirs() }.resolve("sami-backup.zip")
        ZipOutputStream(out.outputStream()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("prefs.json", plain.toString().toByteArray())
            put("secrets.json", secrets.toString().toByteArray())
            listOf("documents", "faces", "wake").forEach { dir ->
                File(context.filesDir, dir).walkTopDown().filter { it.isFile }.forEach { f -> put("files/" + f.relativeTo(context.filesDir).invariantSeparatorsPath, f.readBytes()) }
            }
            listOf("our_photos.json", "ocr_index.json").map { File(context.filesDir, it) }.filter { it.exists() }.forEach { put("files/${it.name}", it.readBytes()) }
        }
        Log.i("SamiBackup", "exported ${out.length()} bytes")
        return out
    }

    /** Imports files/restore/sami-backup.zip if present (called at app start), then deletes it. */
    fun restoreIfPresent(context: Context): Boolean {
        val zip = File(context.filesDir, "restore/sami-backup.zip")
        if (!zip.exists()) return false
        return runCatching {
            val prefs = SamiPrefs(context)
            val ed = context.getSharedPreferences("sami", Context.MODE_PRIVATE).edit()
            ZipInputStream(zip.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val bytes = z.readBytes()
                    when {
                        e.name == "prefs.json" -> JSONObject(String(bytes)).let { o -> o.keys().forEach { k ->
                            val item = o.getJSONObject(k)
                            when (item.optString("t")) {
                                "Boolean" -> ed.putBoolean(k, item.getBoolean("v")); "Float", "Double" -> ed.putFloat(k, item.getDouble("v").toFloat())
                                "Integer" -> ed.putInt(k, item.getInt("v")); "Long" -> ed.putLong(k, item.getLong("v")); else -> ed.putString(k, item.optString("v"))
                            } } }
                        e.name == "secrets.json" -> JSONObject(String(bytes)).let { s ->
                            ed.apply() // write plain prefs first; secrets are re-encrypted with the new install's Keystore key
                            s.optString("groq_key").takeIf { it.isNotBlank() && it != "null" }?.let { prefs.groqKey = it }
                            s.optString("pv_key").takeIf { it.isNotBlank() && it != "null" }?.let { prefs.picovoiceKey = it }
                            s.optJSONArray("facts")?.let { a -> prefs.facts = (0 until a.length()).map { a.getString(it) } }
                            fun map(k: String) = s.optJSONObject(k)?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap()
                            prefs.payees = map("payees"); prefs.aliases = map("aliases").ifEmpty { prefs.aliases }
                            prefs.learned = map("learned"); prefs.planCache = map("plans")
                        }
                        e.name.startsWith("files/") -> File(context.filesDir, e.name.removePrefix("files/")).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                    }
                    e = z.nextEntry
                }
            }
            ed.apply()
            zip.delete()
            Log.i("SamiBackup", "restored from backup")
            true
        }.getOrElse { Log.e("SamiBackup", "restore failed", it); false }
    }
}

/** `adb shell am broadcast -n com.sami.assistant/.system.BackupReceiver` → writes files/backup/sami-backup.zip */
class BackupReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) { BackupManager.export(context) }
}

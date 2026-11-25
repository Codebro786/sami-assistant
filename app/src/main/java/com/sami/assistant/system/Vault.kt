package com.sami.assistant.system

import android.content.Context
import android.net.Uri
import com.sami.assistant.apps.AppMatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Two small owner-curated collections, stored privately inside Sami:
 * - My documents: photos of Aadhaar, PAN, uni ID card… copied into app storage with a name ("aadhaar", "pan card").
 * - Our photos: photos of the owner and their girlfriend, picked in Sami; one is opened at random on request.
 */
class Vault(private val context: Context) {
    data class Doc(val name: String, val file: File)

    private val docDir = File(context.filesDir, "documents").apply { mkdirs() }
    private val docIndex = File(docDir, "index.json")
    private val usFile = File(context.filesDir, "our_photos.json")

    // ---- documents ------------------------------------------------------------------------------
    fun docs(): List<Doc> = runCatching {
        val a = JSONArray(docIndex.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { Doc(it.getString("name"), File(docDir, it.getString("file"))) }.filter { it.file.exists() }
    }.getOrDefault(emptyList())

    private fun saveDocs(list: List<Doc>) = docIndex.writeText(JSONArray(list.map { JSONObject().put("name", it.name).put("file", it.file.name) }).toString())

    fun addDoc(name: String, uri: Uri): Doc? {
        val ext = context.contentResolver.getType(uri)?.substringAfter('/')?.let { if (it == "jpeg") "jpg" else it }?.take(5) ?: "jpg"
        val f = File(docDir, "doc_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } ?: return null
        val d = Doc(name.trim(), f)
        saveDocs(docs() + d); return d
    }

    fun removeDoc(d: Doc) { d.file.delete(); saveDocs(docs().filter { it.file != d.file }) }

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex("""\s+"""), " ").trim()
    private val spelling = mapOf("aadhar" to "aadhaar", "adhar" to "aadhaar", "adhaar" to "aadhaar", "licence" to "license", "uni" to "university", "college" to "university")
    private fun canon(s: String) = norm(s).split(" ").map { spelling[it] ?: it }.filter { it !in setOf("my", "the", "a", "card", "photo", "pic", "open", "show", "of", "copy") }

    /** Best document for what the user said ("open my adhar card" → "Aadhaar"). */
    fun findDoc(query: String): Doc? {
        val q = canon(query); if (q.isEmpty()) return null
        return docs().map { d ->
            val n = canon(d.name)
            val hits = q.count { w -> n.any { it == w || (w.length >= 4 && it.length >= 4 && AppMatcher.distance(it, w) <= 1) } }
            d to hits
        }.filter { it.second > 0 && it.second >= minOf(q.size, canon(it.first.name).size) }.maxByOrNull { it.second }?.first
    }

    // ---- our photos -----------------------------------------------------------------------------
    fun ourPhotos(): List<Uri> = runCatching { JSONArray(usFile.readText()).let { a -> (0 until a.length()).map { Uri.parse(a.getString(it)) } } }.getOrDefault(emptyList())

    fun addOurPhotos(uris: List<Uri>) {
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        usFile.writeText(JSONArray((ourPhotos() + uris).distinct().map { it.toString() }).toString())
    }

    fun clearOurPhotos() { usFile.delete() }
}

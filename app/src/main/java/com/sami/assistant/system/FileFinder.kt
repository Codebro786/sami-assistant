package com.sami.assistant.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sami.assistant.apps.AppMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Finds documents/photos anywhere on the phone ("open my aadhaar card photo", "academic calendar").
 * 1) Fuzzy file-name search over shared storage. 2) If nothing matches, on-device text recognition (OCR)
 * of photos and PDF first pages; OCR results are cached so each file is read only once. Nothing leaves the phone.
 */
class FileFinder(private val context: Context) {
    data class Hit(val file: File, val reason: String)

    private val root = Environment.getExternalStorageDirectory()
    private val exts = setOf("pdf", "jpg", "jpeg", "png", "webp", "heic", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv")
    private val imageExts = setOf("jpg", "jpeg", "png", "webp")
    private val ocrCache = File(context.filesDir, "ocr_index.json")
    private var index: List<File> = emptyList(); private var indexedAt = 0L

    fun hasAccess() = Environment.isExternalStorageManager()

    /** Words people say that aren't part of a document name. */
    private val filler = setOf("my", "me", "the", "a", "an", "of", "open", "show", "find", "photo", "pic", "picture", "image", "file", "pdf",
        "document", "doc", "copy", "scan", "please", "wala", "vala", "ka", "ki", "ke", "kholo", "dikhao", "university", "uni", "college", "our", "this", "that")

    /** Spelling variants and the words that prove a document's identity when reading its text. */
    private val synonyms = mapOf(
        "aadhaar" to listOf("aadhaar", "aadhar", "adhar", "adhaar", "uidai", "aadhaarcard"),
        "pan" to listOf("pan", "pancard"),
        "calendar" to listOf("calendar", "calender", "calander"),
        "marksheet" to listOf("marksheet", "result", "grade", "transcript"),
        "licence" to listOf("licence", "license", "dl"),
        "resume" to listOf("resume", "cv"),
        "timetable" to listOf("timetable", "time table", "schedule"),
    )
    private val ocrProof = mapOf(
        "aadhaar" to listOf("aadhaar", "uidai", "government of india", "unique identification", "आधार"),
        "pan" to listOf("income tax department", "permanent account number"),
        "calendar" to listOf("academic calendar", "calendar"),
        "licence" to listOf("driving licence", "driving license"),
        "marksheet" to listOf("marks", "grade card", "sgpa", "cgpa"),
    )

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()

    fun tokens(query: String): List<String> {
        val t = norm(query).split(" ").filter { it.isNotBlank() && it !in filler }.map { t -> synonyms.entries.firstOrNull { (_, v) -> t in v }?.key ?: t }.distinct()
        // With a known document type, generic words ("card", "copy", "front") only get in the way.
        return if (t.any { it in ocrProof }) t.filter { it in ocrProof || it !in setOf("card", "copy", "front", "back", "id", "scanned", "original") } else t
    }

    /** "photo" → prefer images, "pdf" → prefer PDFs. */
    private fun preferKind(query: String): String? = norm(query).let { q -> when {
        Regex("""(^|\s)(photo|pic|picture|image|jpg)(\s|$)""").containsMatchIn(q) -> "image"
        Regex("""(^|\s)pdf(\s|$)""").containsMatchIn(q) -> "pdf"
        else -> null } }

    private fun walk(): List<File> {
        if (System.currentTimeMillis() - indexedAt < 10 * 60_000 && index.isNotEmpty()) return index
        val out = ArrayList<File>(20_000)
        fun go(d: File, depth: Int) {
            if (depth > 9) return
            val kids = d.listFiles() ?: return
            for (f in kids) {
                val n = f.name
                if (n.startsWith(".")) continue
                if (f.isDirectory) { if (depth == 0 && n == "Android") continue; go(f, depth + 1) }
                else if (f.extension.lowercase() in exts) out += f
            }
        }
        go(root, 0)
        index = out.sortedByDescending { it.lastModified() }; indexedAt = System.currentTimeMillis()
        Log.i("SamiFiles", "indexed ${out.size} files")
        return index
    }

    private fun nameMatches(f: File, toks: List<String>): Boolean {
        val words = norm(f.nameWithoutExtension).split(" ") + norm(f.parentFile?.name ?: "").split(" ")
        val joined = words.joinToString("")
        return toks.all { t ->
            val variants = synonyms[t] ?: listOf(t)
            variants.any { v -> joined.contains(v.replace(" ", "")) || words.any { w -> w.length >= 5 && v.length >= 5 && AppMatcher.distance(w, v) <= 1 } }
        }
    }

    suspend fun search(query: String, allowOcr: Boolean = true): Hit? = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext null
        val toks = tokens(query)
        if (toks.isEmpty()) return@withContext null
        val files = walk()
        val kind = preferKind(query)
        fun rank(list: List<File>) = list.sortedBy { f -> val img = f.extension.lowercase() in imageExts
            when (kind) { "image" -> if (img) 0 else 1; "pdf" -> if (f.extension.lowercase() == "pdf") 0 else 1; else -> 0 } }
        rank(files.filter { nameMatches(it, toks) }).firstOrNull()?.let { return@withContext Hit(it, "name") }
        if (!allowOcr) return@withContext null
        ocrSearch(toks, rank(files))
    }

    // ---- OCR ---------------------------------------------------------------------------------
    private fun loadCache(): JSONObject = runCatching { JSONObject(ocrCache.readText()) }.getOrDefault(JSONObject())

    private suspend fun ocrSearch(toks: List<String>, files: List<File>): Hit? {
        val cache = loadCache()
        val proofs = toks.flatMap { ocrProof[it] ?: listOf(it) }
        val docToks = toks.filter { it in ocrProof }
        fun matches(text: String) = if (docToks.isNotEmpty()) docToks.all { t -> ocrProof[t]!!.any { text.contains(it) } }
            else toks.all { t -> text.contains(t) } || (toks.size == 1 && proofs.any { text.contains(it) })
        // 1) already-read files
        for (f in files) cache.optString(f.absolutePath, null)?.let { if (matches(it)) return Hit(f, "text") }
        // 2) read new candidates: documents & downloads first, then recent photos (limits keep a voice reply quick)
        val candidates = files.filter { !cache.has(it.absolutePath) && (it.extension.lowercase() in imageExts || it.extension.lowercase() == "pdf") }
            .filter { !it.absolutePath.contains("/Screenshots/") }
            .sortedBy { if (it.absolutePath.contains("/Download") || it.absolutePath.contains("/Documents") || it.absolutePath.contains("WhatsApp Documents")) 0 else 1 }
            .take(120)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            for (f in candidates) {
                val text = runCatching { readText(recognizer, f) }.getOrDefault("").lowercase()
                cache.put(f.absolutePath, text.take(600))
                if (matches(text)) { ocrCache.writeText(cache.toString()); return Hit(f, "text") }
            }
        } finally { recognizer.close(); ocrCache.writeText(cache.toString()) }
        return null
    }

    /** Background pre-reading (while charging) so later searches are instant. */
    suspend fun prefetchOcr(limit: Int) = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext
        val cache = loadCache()
        val todo = walk().filter { !cache.has(it.absolutePath) && (it.extension.lowercase() in imageExts || it.extension.lowercase() == "pdf") && !it.absolutePath.contains("/Screenshots/") }.take(limit)
        if (todo.isEmpty()) return@withContext
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try { todo.forEach { f -> cache.put(f.absolutePath, runCatching { readText(recognizer, f) }.getOrDefault("").lowercase().take(600)) } }
        finally { recognizer.close(); ocrCache.writeText(cache.toString()) }
    }

    private suspend fun readText(recognizer: com.google.mlkit.vision.text.TextRecognizer, f: File): String {
        val bmp = if (f.extension.lowercase() == "pdf") renderPdf(f) else decode(f, 1600)
        bmp ?: return ""
        return try { recognizer.process(InputImage.fromBitmap(bmp, 0)).await().text } finally { bmp.recycle() }
    }

    private fun renderPdf(f: File): Bitmap? = runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                r.openPage(0).use { p ->
                    val scale = 1400f / maxOf(p.width, p.height)
                    Bitmap.createBitmap((p.width * scale).toInt(), (p.height * scale).toInt(), Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(android.graphics.Color.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    companion object {
        fun decode(f: File, maxSide: Int): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth <= 0) return null
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
            return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        }
    }
}

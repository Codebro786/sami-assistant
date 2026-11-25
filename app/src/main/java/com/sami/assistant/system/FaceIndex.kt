package com.sami.assistant.system

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * On-device face recognition for "open a photo of me and her".
 * ML Kit finds faces; MobileFaceNet (Qualcomm AI Hub build, Apache-2.0) turns each face into a 128-number
 * fingerprint. The user labels faces once ("Me", "Her"); the gallery is then scanned (while charging or on request)
 * and each photo's matched people are stored locally. No photo or face data ever leaves the phone.
 */
class FaceIndex(private val context: Context) {
    data class FaceCrop(val bitmap: Bitmap, val embedding: FloatArray)

    private val dir = File(context.filesDir, "faces").apply { mkdirs() }
    private val peopleFile = File(dir, "people.json")      // label -> [embeddings]
    private val indexFile = File(dir, "gallery_index.json") // mediaId -> [labels]
    private val lock = Mutex()

    private val detector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setMinFaceSize(0.08f).build())
    }
    private val interpreter by lazy {
        val fd = context.assets.openFd("mobile_facenet.tflite")
        val buf = java.io.FileInputStream(fd.fileDescriptor).channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        Interpreter(buf, Interpreter.Options().setNumThreads(2))
    }

    // ---- embeddings ----------------------------------------------------------------------------
    private fun tensor(face: Bitmap): ByteBuffer {
        val s = Bitmap.createScaledBitmap(face, 112, 112, true)
        val px = IntArray(112 * 112); s.getPixels(px, 0, 112, 0, 0, 112, 112)
        val b = ByteBuffer.allocateDirect(3 * 112 * 112 * 4).order(ByteOrder.nativeOrder())
        for (c in 0 until 3) for (p in px) b.putFloat(((p shr (16 - 8 * c)) and 0xFF) / 255f) // NCHW, RGB, 0..1
        b.rewind(); return b
    }

    @Synchronized private fun embed(face: Bitmap): FloatArray {
        val t = tensor(face)
        val out = Array(2) { FloatArray(128) }
        interpreter.runForMultipleInputsOutputs(arrayOf(t, tensor(face)), mapOf(0 to out))
        return normalize(out[0])
    }

    private fun normalize(v: FloatArray): FloatArray { val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f); return FloatArray(v.size) { v[it] / n } }
    private fun cos(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }

    /** Faces in an image, largest first, each cropped with a margin and embedded. */
    suspend fun faces(bmp: Bitmap): List<FaceCrop> = withContext(Dispatchers.Default) {
        val found = runCatching { detector.process(InputImage.fromBitmap(bmp, 0)).await() }.getOrDefault(emptyList())
        found.sortedByDescending { it.boundingBox.width() }.take(6).mapNotNull { f ->
            val r = Rect(f.boundingBox); val m = (r.width() * 0.15f).toInt()
            r.inset(-m, -m); if (!r.intersect(0, 0, bmp.width, bmp.height) || r.width() < 40) return@mapNotNull null
            val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
            FaceCrop(crop, embed(crop))
        }
    }

    // ---- people (enrolment) ----------------------------------------------------------------------
    private fun people(): Map<String, List<FloatArray>> = runCatching {
        val o = JSONObject(peopleFile.readText())
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { e -> FloatArray(e.length()) { e.getDouble(it).toFloat() } } } } }
    }.getOrDefault(emptyMap())

    fun enrolledCounts(): Map<String, Int> = people().mapValues { it.value.size }

    fun addSample(label: String, embedding: FloatArray) {
        val all = people().toMutableMap()
        all[label] = (all[label].orEmpty() + embedding).takeLast(20)
        val o = JSONObject(); all.forEach { (k, v) -> o.put(k, JSONArray(v.map { e -> JSONArray(e.map { it.toDouble() }) })) }
        peopleFile.writeText(o.toString())
        indexFile.delete() // labels changed → rescan
    }

    fun reset() { peopleFile.delete(); indexFile.delete() }

    /** Best label for a face, or null. Needs to be clearly closer to one person than to the others. */
    private fun identify(e: FloatArray, ppl: Map<String, List<FloatArray>>): String? {
        val scores = ppl.mapValues { (_, samples) -> samples.map { cos(e, it) }.sortedDescending().take(3).average().toFloat() }
        val best = scores.maxByOrNull { it.value } ?: return null
        val second = scores.filterKeys { it != best.key }.values.maxOrNull() ?: 0f
        return if (best.value >= MATCH && best.value - second >= 0.06f) best.key else null
    }

    // ---- gallery index ----------------------------------------------------------------------------
    private fun loadIndex(): JSONObject = runCatching { JSONObject(indexFile.readText()) }.getOrDefault(JSONObject())

    fun indexedCount() = loadIndex().length()

    /** Scan up to [limit] not-yet-scanned photos (newest first). Returns how many were scanned. */
    suspend fun scan(limit: Int): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            val ppl = people(); if (ppl.size < 2) return@withContext 0
            val idx = loadIndex()
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val todo = mutableListOf<Long>()
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH), null, null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
                while (c.moveToNext() && todo.size < limit) {
                    val id = c.getLong(0); val path = c.getString(1).orEmpty()
                    if (!idx.has(id.toString()) && !path.contains("Screenshots") && !path.contains("WhatsApp Stickers")) todo += id
                }
            }
            for (id in todo) {
                val labels = runCatching {
                    val bmp = context.contentResolver.openInputStream(ContentUris.withAppendedId(uri, id))?.use { s ->
                        val bytes = s.readBytes()
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                        var smp = 1; while (maxOf(o.outWidth, o.outHeight) / (smp * 2) >= 1280) smp *= 2
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = smp })
                    } ?: return@runCatching emptySet<String>()
                    try { faces(bmp).mapNotNull { identify(it.embedding, ppl) }.toSet() } finally { bmp.recycle() }
                }.getOrDefault(emptySet())
                idx.put(id.toString(), JSONArray(labels.toList()))
            }
            indexFile.writeText(idx.toString())
            Log.i("SamiFaces", "scanned ${todo.size}, total ${idx.length()}")
            todo.size
        }
    }

    /** Random photo containing all [labels], or null. */
    fun randomWith(labels: Set<String>): Uri? {
        val idx = loadIndex()
        val matches = idx.keys().asSequence().filter { k -> idx.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) }.toSet().containsAll(labels) } }.toList()
        val pick = matches.randomOrNull() ?: return null
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pick.toLong())
    }

    fun countWith(labels: Set<String>): Int {
        val idx = loadIndex()
        return idx.keys().asSequence().count { k -> idx.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) }.toSet().containsAll(labels) } }
    }

    companion object { const val MATCH = 0.45f; const val ME = "me"; const val HER = "her" }
}

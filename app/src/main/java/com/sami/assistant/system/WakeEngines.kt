package com.sami.assistant.system

import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.util.Log
import com.sami.assistant.core.SamiPrefs
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** A local, always-on keyword spotter. No audio leaves the device. */
interface WakeEngine {
    val name: String
    fun start()
    fun stop()
    fun release()
}

/**
 * Default engine — Vosk (Apache 2.0), no account or key needed. Runs a tiny grammar-restricted
 * recognizer (Indian-English model) that can only output the wake phrases or "[unk]",
 * which keeps CPU low and rejects ordinary speech.
 */
class VoskWakeEngine private constructor(
    private val model: Model,
    private val sensitivity: Float,
    private val onWake: () -> Unit,
    private val onError: (String) -> Unit,
) : WakeEngine {
    override val name = "Vosk (on-device, no account)"
    private var service: SpeechService? = null

    companion object {
        private const val TAG = "SamiWakeVosk"
        /** Variants the recogniser may hear for "Sami"; words missing from the model vocabulary are ignored by Vosk. */
        val GRAMMAR = """["hey sami", "hey sammy", "hi sami", "hi sammy", "sami", "sammy", "samee", "hey sam me", "[unk]"]"""
        private val WAKE = Regex("""(^|\s)(sami|sammy|samee|sam me)($|\s)""")

        @Volatile private var cachedModel: Model? = null

        suspend fun create(context: Context, sensitivity: Float, onWake: () -> Unit, onError: (String) -> Unit): VoskWakeEngine {
            val model = cachedModel ?: suspendCoroutine<Model> { cont ->
                // Copies the bundled model from assets into private storage once (re-copied when its uuid changes).
                StorageService.unpack(context.applicationContext, "model-en-in", "vosk-model",
                    { m -> cont.resume(m) },
                    { e -> cont.resumeWithException(IllegalStateException("Vosk model unpack failed: ${e.message}", e)) })
            }.also { cachedModel = it }
            return VoskWakeEngine(model, sensitivity, onWake, onError).also { it.appContext = context.applicationContext }
        }

        /** Pure decision logic, unit-tested: does this Vosk JSON result count as a wake? */
        fun isWake(resultJson: String, sensitivity: Float, mediaPlaying: Boolean = false): Boolean {
            val o = runCatching { JSONObject(resultJson) }.getOrNull() ?: return false
            val text = o.optString("text").trim()
            if (!WAKE.containsMatchIn(text)) return false
            val words = o.optJSONArray("result") ?: return true // no per-word confidence available
            // With a tiny grammar Vosk splits confidence between "sami"/"sammy", so a clear hit reads ~0.5.
            // Higher sensitivity → lower confidence needed. 0.3 → 0.67, 0.6 → 0.49, 0.9 → 0.31
            // Music under the voice lowers Vosk's confidence, so accept a bit less while media plays.
            val needed = 0.85 - sensitivity * 0.6 - if (mediaPlaying) 0.15 else 0.0
            for (i in 0 until words.length()) {
                val w = words.getJSONObject(i)
                if (WAKE.containsMatchIn(w.optString("word")) && w.optDouble("conf", 1.0) >= needed) return true
            }
            return false
        }
    }

    private val listener = object : RecognitionListener {
        private var firedOnPartial = false
        override fun onPartialResult(hypothesis: String?) {
            // Fast path: the full two-word phrase in a partial result wakes immediately (no wait for silence).
            val text = hypothesis?.let { runCatching { JSONObject(it).optString("partial") }.getOrNull() }.orEmpty()
            if (!firedOnPartial && Regex("""(hey|hi)\s+(sami|sammy)""").containsMatchIn(text)) {
                firedOnPartial = true; Log.i(TAG, "wake (partial): $text"); onWake()
            }
        }
        override fun onResult(hypothesis: String?) {
            if (hypothesis == null) return
            if (!hypothesis.contains("\"text\" : \"\"")) Log.d(TAG, "heard: ${hypothesis.replace(Regex("""\s+"""), " ")}")
            if (firedOnPartial) { firedOnPartial = false; return }
            if (isWake(hypothesis, sensitivity)) { Log.i(TAG, "wake: $hypothesis"); onWake() }
        }
        override fun onFinalResult(hypothesis: String?) { onResult(hypothesis) }
        override fun onError(exception: Exception?) { Log.e(TAG, "error", exception); onError(exception?.message ?: "audio error") }
        override fun onTimeout() {}
    }

    // ---- Energy-gated capture loop ----------------------------------------------------------
    // Running the recogniser on every 100 ms of audio costs ~20% of a core. Instead we compute a
    // cheap RMS per frame against an adaptive noise floor and only feed Vosk while someone is
    // speaking (with 400 ms of pre-roll so "Hey" isn't clipped). Silence costs almost nothing.
    @Volatile private var running = false
    private var appContext: Context? = null
    private var thread: Thread? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @android.annotation.SuppressLint("MissingPermission") // RECORD_AUDIO is checked before the service starts
    override fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "SamiWake").apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }

    private fun loop() {
        val am = appContext?.getSystemService(android.media.AudioManager::class.java)
        while (running) {
            // Music/video playing → "voice call" mic mode: the phone's call-grade echo canceller removes its own
            // speaker output (the same path used on calls). Otherwise the normal recognition mic (best for quiet rooms).
            val media = am?.isMusicActive == true
            // VOICE_COMMUNICATION returns near-silence on this phone outside a real call (measured rms 0–1), so always use recognition.
            captureOnce(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, media)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun captureOnce(source: Int, startedWithMedia: Boolean) {
        val rate = 16000; val frame = 1600 // 100 ms
        val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            android.media.AudioRecord(source, rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 4))
        } catch (e: Exception) { main.post { onError("Mic unavailable: ${e.message}") }; running = false; return }
        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) { rec.release(); main.post { onError("Mic unavailable") }; running = false; return }
        // Echo cancellation removes the phone's own music/video from the mic so "Hey Sami" is heard over it.
        val aec = runCatching { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val ns = runCatching { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        Log.i(TAG, "mic source=recognition media=$startedWithMedia aec=${aec != null} ns=${ns != null}")
        val am = appContext?.getSystemService(android.media.AudioManager::class.java)
        var mediaCheck = 0; var mediaPlaying = startedWithMedia
        val recognizer = Recognizer(model, rate.toFloat(), GRAMMAR).apply { setWords(true) }
        val buf = ShortArray(frame)
        val preroll = ArrayDeque<ShortArray>()
        var floor = 200.0; var active = false; var silentFrames = 0; var activeFrames = 0; var firedPartial = false
        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                val rms = kotlin.math.sqrt(sum / n)
                if (mediaCheck++ % 20 == 0) {
                    mediaPlaying = am?.isMusicActive == true // every ~2 s, cheap
                    if (mediaPlaying != startedWithMedia && !active) break // switch mic mode
                }
                // While media plays the floor includes leftover music, so a smaller margin is enough to spot a voice.
                val speech = if (mediaPlaying) rms > maxOf(floor * 1.5, 250.0) else rms > maxOf(floor * 2.5, 350.0)
                if (mediaCheck % (if (mediaPlaying) 10 else 100) == 1) Log.d(TAG, "diag rms=${rms.toInt()} floor=${floor.toInt()} media=$mediaPlaying active=$active")
                // While music/video plays the energy gate can't tell voice from music, so stream continuously
                // (costs CPU only during playback; back to the cheap gate when it stops).
                if (mediaPlaying && !active) { active = true; silentFrames = 0; activeFrames = 0; firedPartial = false; preroll.forEach { recognizer.acceptWaveForm(it, it.size) }; preroll.clear() }
                if (!active) {
                    floor = floor * 0.97 + rms * 0.03 // adapt to room noise while idle
                    preroll.addLast(buf.copyOf(n)); if (preroll.size > 4) preroll.removeFirst()
                    if (!speech) continue
                    active = true; silentFrames = 0; activeFrames = 0; firedPartial = false
                    preroll.forEach { recognizer.acceptWaveForm(it, it.size) }; preroll.clear()
                    continue
                }
                activeFrames++
                silentFrames = if (speech || mediaPlaying) 0 else silentFrames + 1
                val endOfUtterance = recognizer.acceptWaveForm(buf, n)
                if (!firedPartial && activeFrames % 3 == 0) {
                    val p = runCatching { JSONObject(recognizer.partialResult).optString("partial") }.getOrDefault("")
                    // Over music a lone "sami" is accepted too (the grammar only knows wake words + [unk]).
                    val hit = Regex("""(hey|hi)\s+(sami|sammy)""").containsMatchIn(p) ||
                        (mediaPlaying && Regex("""(^|\s)(sami|sammy)($|\s)""").containsMatchIn(p))
                    if (hit) { firedPartial = true; Log.i(TAG, "wake (partial${if (mediaPlaying) ", media" else ""}): $p"); main.post { onWake() } }
                }
                // End the utterance on Vosk's endpoint, ~0.8 s of silence, or a 6 s cap.
                if (endOfUtterance || silentFrames >= 8 || activeFrames >= (if (mediaPlaying) 80 else 60)) {
                    val res = if (endOfUtterance) recognizer.result else recognizer.finalResult
                    if (!firedPartial && isWake(res, sensitivity, mediaPlaying)) { Log.i(TAG, "wake: $res"); main.post { onWake() } }
                    else if (!res.contains("\"text\" : \"\"")) Log.d(TAG, "heard: ${res.replace(Regex("""\s+"""), " ")}")
                    recognizer.reset(); active = false; firedPartial = false
                }
            }
        } finally {
            runCatching { rec.stop() }; rec.release(); recognizer.close(); aec?.release(); ns?.release()
        }
    }

    override fun stop() { running = false; thread?.join(500); thread = null; service = null }
    override fun release() = stop()
}

/** Optional engine — Picovoice Porcupine, used only if the user adds their own AccessKey + .ppn. */
class PorcupineWakeEngine(context: Context, prefs: SamiPrefs, onWake: () -> Unit, onError: (String) -> Unit) : WakeEngine {
    override val name = "Picovoice Porcupine"
    private val manager: PorcupineManager = PorcupineManager.Builder()
        .setAccessKey(prefs.picovoiceKey)
        .setKeywordPath(prefs.keywordFile.absolutePath)
        .setSensitivity(prefs.sensitivity)
        .setErrorCallback { e -> onError(e.message ?: "engine error") }
        .build(context.applicationContext) { onWake() }

    override fun start() = manager.start()
    override fun stop() = manager.stop()
    override fun release() { runCatching { manager.stop() }; manager.delete() }
}

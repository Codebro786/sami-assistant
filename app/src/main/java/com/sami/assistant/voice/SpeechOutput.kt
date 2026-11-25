package com.sami.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Android TextToSpeech wrapper. [speak] suspends until done; cancelling the coroutine stops speech.
 * Speaks as an assistant voice and briefly ducks other audio (music/video) so Sami is always audible.
 */
class SpeechOutput(context: Context) {
    private val app = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(app) { status ->
        Log.i("SamiTTS", "engine init status=$status")
        ready.complete(status == TextToSpeech.SUCCESS)
    }
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()

    var rate = 1.05f
    var pitch = 1.0f
    /** Last error, shown in Settings when Sami can't speak. */
    @Volatile var lastError: String? = null; private set

    init { tts.setAudioAttributes(attrs) }

    private fun pickLanguage(want: Locale): Locale {
        // Hindi voice data is often not downloaded; isLanguageAvailable alone can lie, so also check a voice exists.
        val ok = tts.isLanguageAvailable(want) >= TextToSpeech.LANG_AVAILABLE &&
            runCatching { tts.voices?.any { it.locale.language == want.language && !it.isNetworkConnectionRequired && !it.features.contains("notInstalled") } }.getOrNull() != false
        return if (ok) want else Locale.forLanguageTag("en-IN").takeIf { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.US
    }

    suspend fun speak(text: String, locale: Locale = if (com.sami.assistant.core.Lang.hinglish) Locale.forLanguageTag("hi-IN") else Locale.forLanguageTag("en-IN")): Boolean {
        if (text.isBlank()) return false
        if (!ready.await()) { lastError = "No text-to-speech engine. Install/enable Google Speech Services."; Log.e("SamiTTS", lastError!!); return false }
        val spoken = text.take(700)
        val lang = pickLanguage(locale)
        val r = tts.setLanguage(lang)
        if (r < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US)
        tts.setSpeechRate(rate); tts.setPitch(pitch)
        val id = UUID.randomUUID().toString()
        // Android 16 "audio hardening" mutes the TTS engine's own playback because that engine is a background app.
        // So the engine only renders to a file and Sami (foreground) plays it itself.
        val file = java.io.File(app.cacheDir, "tts_$id.wav")
        val rendered = suspendCancellableCoroutine<Boolean> { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { onError(utteranceId, -1) }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    lastError = "Speech error $errorCode"; Log.e("SamiTTS", "render error $errorCode ($lang)")
                    if (utteranceId == id && cont.isActive) cont.resume(false)
                }
            })
            cont.invokeOnCancellation { tts.stop() }
            val res = tts.synthesizeToFile(spoken, Bundle(), file, id)
            if (res != TextToSpeech.SUCCESS) { lastError = "TTS refused to speak ($res)"; Log.e("SamiTTS", lastError!!); if (cont.isActive) cont.resume(false) }
        }
        if (!rendered || !file.exists() || file.length() < 100) { file.delete(); return false }
        audio.requestAudioFocus(focus)
        try {
            return suspendCancellableCoroutine { cont ->
                val mp = android.media.MediaPlayer()
                player = mp
                fun finish(ok: Boolean) { runCatching { mp.release() }; if (player === mp) player = null; file.delete(); if (cont.isActive) cont.resume(ok) }
                try {
                    mp.setAudioAttributes(attrs)
                    mp.setDataSource(file.absolutePath)
                    mp.setOnCompletionListener { finish(true) }
                    mp.setOnErrorListener { _, what, extra -> lastError = "Playback error $what/$extra"; Log.e("SamiTTS", lastError!!); finish(false); true }
                    mp.prepare(); mp.start()
                    lastError = null
                } catch (e: Exception) { lastError = "Playback failed: ${e.message}"; Log.e("SamiTTS", "play", e); finish(false) }
                cont.invokeOnCancellation { runCatching { mp.stop() }; finish(false) }
            }
        } finally { audio.abandonAudioFocusRequest(focus) }
    }

    @Volatile private var player: android.media.MediaPlayer? = null

    fun stop() { tts.stop(); runCatching { player?.stop() } }
    fun shutdown() { tts.shutdown() }
}

package com.sami.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.sami.assistant.system.Recognizers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data object SpeechStarted : SpeechEvent
    /** Normalised 0..1 input level for the audio-reactive orb. */
    data class Level(val value: Float) : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Error(val code: Int, val message: String) : SpeechEvent
}

/**
 * Thin wrapper around Android [SpeechRecognizer]. Audio goes to the system recognition
 * service (Google on the OnePlus 12); Sami itself never records or uploads audio.
 * Must be collected on the main thread.
 */
class SpeechInput(private val context: Context) {

    fun isAvailable() = Recognizers.external(context) != null

    /** One utterance. The flow completes after a Final or Error event. */
    fun listen(languageTag: String = "en-IN"): Flow<SpeechEvent> = callbackFlow {
        // Explicit component: once Sami is the assistant it is also the default recognizer.
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context, Recognizers.external(context))
        var done = false
        fun finish(e: SpeechEvent) { if (!done) { done = true; trySend(e); close() } }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(SpeechEvent.Ready) }
            override fun onBeginningOfSpeech() { trySend(SpeechEvent.SpeechStarted) }
            override fun onRmsChanged(rmsdB: Float) { trySend(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { trySend(SpeechEvent.Partial(it)) }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) finish(SpeechEvent.Error(SpeechRecognizer.ERROR_NO_MATCH, describe(SpeechRecognizer.ERROR_NO_MATCH)))
                else finish(SpeechEvent.Final(text))
            }
            override fun onError(error: Int) { finish(SpeechEvent.Error(error, describe(error))) }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            // Hinglish: allow Hindi as a secondary language where the recognizer supports it.
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("en-IN", "hi-IN"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer.startListening(intent)

        awaitClose {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    private fun describe(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection right now."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy. Try again."
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "That language isn't available on this phone."
        else -> "Speech recognition error ($code)."
    }
}

package com.sami.assistant.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.sami.assistant.AssistOverlayActivity
import com.sami.assistant.MainActivity

/**
 * Bound by the system while Sami is the selected Digital Assistant app. Lets Sami open
 * from the assist gesture / power-button long-press and (via [showSession]) from the
 * wake-word service even while another app is in front or the phone is locked.
 */
class SamiVoiceInteractionService : VoiceInteractionService() {
    companion object {
        @Volatile var instance: SamiVoiceInteractionService? = null
            private set

        /** Ask the system to open an assistant session. Returns false if Sami isn't the assistant. */
        fun trigger(): Boolean {
            val vis = instance ?: return false
            return runCatching { vis.showSession(Bundle().apply { putBoolean("wake", true) }, 0); true }
                .onFailure { Log.w("SamiVIS", "showSession failed", it) }.getOrDefault(false)
        }
    }

    override fun onReady() { super.onReady(); instance = this; Log.i("SamiVIS", "ready") }
    override fun onShutdown() { instance = null; super.onShutdown() }
}

class SamiSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SamiSession(this)
}

/** Launches the Sami UI as the assistant activity (allowed over other apps and on the lock screen). */
class SamiSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(
            Intent(context, AssistOverlayActivity::class.java).setAction(MainActivity.ACTION_LISTEN)
                // always a fresh, visible overlay (reusing a closing instance left it invisible)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            Bundle()
        )
        hide()
    }
}

/**
 * Android requires an assistant app to declare a RecognitionService, and selecting Sami
 * as assistant also makes it the default recognizer. This forwards recognition to an
 * installed Google recognizer so other apps' voice input keeps working.
 */
class SamiRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val component = Recognizers.external(this)
        if (component == null) { listener.error(SpeechRecognizer.ERROR_CLIENT); return }
        delegate?.destroy()
        delegate = SpeechRecognizer.createSpeechRecognizer(this, component).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) = listener.readyForSpeech(p ?: Bundle())
                override fun onBeginningOfSpeech() = listener.beginningOfSpeech()
                override fun onRmsChanged(v: Float) = listener.rmsChanged(v)
                override fun onBufferReceived(b: ByteArray?) { b?.let { listener.bufferReceived(it) } }
                override fun onEndOfSpeech() = listener.endOfSpeech()
                override fun onError(e: Int) = listener.error(e)
                override fun onResults(r: Bundle?) = listener.results(r ?: Bundle())
                override fun onPartialResults(r: Bundle?) = listener.partialResults(r ?: Bundle())
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(recognizerIntent)
        }
    }
    override fun onStopListening(listener: Callback) { delegate?.stopListening() }
    override fun onCancel(listener: Callback) { delegate?.cancel() }
    override fun onDestroy() { delegate?.destroy(); super.onDestroy() }
}

object Recognizers {
    private val preferred = listOf("com.google.android.tts", "com.google.android.googlequicksearchbox", "com.google.android.as")

    /** A recognition service that is not Sami's own (avoids recursion when Sami is the default). */
    fun external(context: Context): ComponentName? {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
        return preferred.firstNotNullOfOrNull { p -> services.firstOrNull { it.packageName == p } } ?: services.firstOrNull()
    }
}

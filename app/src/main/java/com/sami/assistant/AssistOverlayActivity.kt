package com.sami.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.sami.assistant.ui.confirmCard
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sami.assistant.core.OrbState
import com.sami.assistant.core.UiEvent
import com.sami.assistant.system.WakeWordService
import com.sami.assistant.ui.AssistOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Siri-style invocation: a translucent window over whatever app is open (or the lock screen).
 * Opened by the wake word, assist gesture / power-button hold, Quick Settings tile and shortcut.
 * Closes itself when the conversation ends, when you tap outside, or when Sami opens another app.
 */
class AssistOverlayActivity : FragmentActivity() {
    companion object {
        @Volatile var visible = false
            private set
    }

    private val assistant by lazy { (application as SamiApp).container.assistant }
    private var started = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Belt-and-braces for the lock screen: show over keyguard and wake the screen even if the manifest flags are ignored.
        setShowWhenLocked(true); setTurnScreenOn(true)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        setContent {
            AssistOverlay(assistant, onDismiss = { assistant.cancel(); finish() }, onOpenApp = {
                assistant.cancel()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }, onConfirm = { card, input -> confirmCard(assistant, card, input) })
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { assistant.events.collect { if (it is UiEvent.GetOutOfTheWay) finishQuietly() } }
                // Auto-close shortly after the conversation goes idle.
                assistant.state.collectLatest { s ->
                    if (started && s.orb == OrbState.IDLE) { delay(1800); finishQuietly() }
                }
            }
        }
        if (savedInstanceState == null) begin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isFinishing) {
            // A new invocation landed on an instance that is closing (the cause of the "listening but invisible" bug):
            // start a fresh overlay instead of reusing this one.
            startActivity(Intent(this, AssistOverlayActivity::class.java).setAction(MainActivity.ACTION_LISTEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            return
        }
        begin() // invoked again while open → listen again
    }

    override fun onStart() {
        super.onStart()
        com.sami.assistant.system.PaymentGuard.restoreIfLeft(this, packageName); visible = true
        if ((application as SamiApp).container.prefs.wakeEnabled && hasMic()) WakeWordService.start(this)
    }
    override fun onStop() {
        visible = false; super.onStop()
        // Another app came to front (e.g. "open WhatsApp") — don't linger behind it.
        if (!isChangingConfigurations) finish()
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun begin() {
        if (!hasMic()) { micPermission.launch(Manifest.permission.RECORD_AUDIO); return }
        started = true
        assistant.startSession()
    }

    private fun finishQuietly() {
        if (!isFinishing) { finish(); overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, android.R.anim.fade_out) }
    }
}

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
import com.sami.assistant.core.UiEvent
import com.sami.assistant.system.WakeWordService
import com.sami.assistant.ui.SamiRoot
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    companion object {
        const val ACTION_LISTEN = "com.sami.assistant.LISTEN"
        const val ACTION_START_WAKE = "com.sami.assistant.START_WAKE"
        @Volatile var visible = false
            private set
    }

    private val vm by lazy { (application as SamiApp).container.assistant }
    private var startAfterGrant = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && startAfterGrant) vm.startSession()
        startAfterGrant = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SamiRoot(vm, onListen = { listen(toggle = true) }, onConfirm = { card, input -> confirmCard(vm, card, input) }) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.events.collect { if (it is UiEvent.GetOutOfTheWay) moveTaskToBack(true) }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        com.sami.assistant.system.PaymentGuard.restoreIfLeft(this, packageName); visible = true
        // Android 14+ only lets a microphone service start while the app is visible — (re)start it here.
        val prefs = (application as SamiApp).container.prefs
        if (prefs.wakeEnabled && hasMic()) WakeWordService.start(this)
    }
    override fun onStop() { visible = false; super.onStop() }

    /** Assist gesture, wake word, QS tile and shortcut always *start* listening. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == ACTION_LISTEN) listen(toggle = false)
        if (intent?.action == ACTION_START_WAKE) { if (hasMic()) WakeWordService.start(this); moveTaskToBack(true) }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun listen(toggle: Boolean) {
        if (hasMic()) { if (toggle) vm.onOrbTap() else vm.startSession() }
        else { startAfterGrant = true; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }
}

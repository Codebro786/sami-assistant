package com.sami.assistant.system

import ai.picovoice.porcupine.PorcupineException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sami.assistant.AssistOverlayActivity
import com.sami.assistant.MainActivity
import com.sami.assistant.R
import com.sami.assistant.core.SamiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Shared, in-process signals between the wake service and the assistant UI. */
object WakeBus {
    /** True while the assistant is using the mic — the wake engine releases it. */
    val assistantActive = MutableStateFlow(false)
    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status.asStateFlow()
    internal fun status(s: String) { _status.value = s }
}

/**
 * Always-on "Hey Sami" detection with Picovoice Porcupine, fully on-device.
 * Runs as a microphone foreground service (persistent notification + system mic indicator).
 * Android 14+ only allows starting it while Sami is visible, so it is started from the app UI.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL = "wake"
        private const val NOTIF_ID = 7
        const val ACTION_STOP = "com.sami.assistant.WAKE_STOP"

        fun start(context: Context) = runCatching { ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java)) }
            .onFailure { WakeBus.status("Couldn't start: ${it.message}") }.isSuccess
        fun stop(context: Context) { context.stopService(Intent(context, WakeWordService::class.java)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: WakeEngine? = null
    private var building = false
    private var listening = false
    private var lastTrigger = 0L

    private val lowBattery = MutableStateFlow(false)
    /** True during the user's sleep window (e.g. 00:00–07:00) when "pause while I sleep" is on. */
    private val asleep = MutableStateFlow(false)
    private fun checkSleep() {
        val prefs = SamiPrefs(this)
        val now = java.time.LocalTime.now()
        val from = runCatching { java.time.LocalTime.parse(prefs.sleepTime) }.getOrDefault(java.time.LocalTime.MIDNIGHT)
        val to = runCatching { java.time.LocalTime.parse(prefs.wakeTime ?: "07:00") }.getOrDefault(java.time.LocalTime.of(7, 0))
        val inWindow = if (from <= to) now >= from && now < to else now >= from || now < to
        val s = prefs.sleepPause && inWindow
        if (s != asleep.value) { asleep.value = s; WakeBus.status(if (s) "Sleeping until ${to} — tap Talk or use the power button" else "Listening for “Hey Sami”") }
    }
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100) * 100 / i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
            val low = SamiPrefs(c).pauseWakeOnLowBattery && level <= 15 && !plugged
            if (plugged && level >= 30) chargingWork()
            if (low != lowBattery.value) { lowBattery.value = low; if (low) WakeBus.status("Paused — battery below 15%") }
        }
    }

    private var lastChargingWork = 0L
    /** While charging: scan new photos for faces and pre-read documents, at most once every 30 min. */
    private fun chargingWork() {
        val now = System.currentTimeMillis(); if (now - lastChargingWork < 30 * 60_000) return
        lastChargingWork = now
        scope.launch(Dispatchers.IO) {
            val app = (application as com.sami.assistant.SamiApp).container.actions.personal
            runCatching { app.files.prefetchOcr(200) }.onFailure { Log.w("SamiWake", "ocr prefetch", it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // TIME_TICK fires once a minute (cheap) — used to enter/leave the sleep window.
        registerReceiver(object : android.content.BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = checkSleep() },
            android.content.IntentFilter(Intent.ACTION_TIME_TICK))
        checkSleep()
        // Bring screen control back after the owner leaves a UPI/banking app.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(3000)
                if (PaymentGuard.isPaused(this@WakeWordService)) PaymentGuard.restoreIfLeft(this@WakeWordService, PaymentGuard.foregroundApp(this@WakeWordService))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SamiPrefs(this).wakeEnabled = false
            stopSelf(); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification("Listening for “Hey Sami”"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android refuses a mic FGS that wasn't started from the foreground (e.g. sticky restart).
            Log.w("SamiWake", "startForeground denied", e)
            WakeBus.status("Paused by Android — open Sami to resume")
            stopSelf(); return START_NOT_STICKY
        }
        if (!building && engine == null) {
            building = true
            scope.launch {
                engine = build()
                building = false
                if (engine == null) { stopSelf(); return@launch }
                // Pause while Sami is talking, and on low battery (unless charging) to protect battery life.
                kotlinx.coroutines.flow.combine(WakeBus.assistantActive, lowBattery, asleep) { active, low, sleep -> active || low || sleep }
                    .collect { pauseIt ->
                        if (pauseIt) pause() else resume()
                        // Show the real reason, not a stale "loading"/"paused while talking".
                        when {
                            asleep.value -> WakeBus.status("Sleeping until ${SamiPrefs(this@WakeWordService).wakeTime ?: "07:00"} — power button and tile still work")
                            lowBattery.value -> WakeBus.status("Paused — battery below 15%")
                        }
                    }
            }
        }
        return START_STICKY
    }

    private suspend fun build(): WakeEngine? {
        val prefs = SamiPrefs(this)
        val onErr: (String) -> Unit = { msg -> Log.e("SamiWake", msg); WakeBus.status("Engine error: $msg") }
        // Porcupine only if the user configured it; otherwise the account-free Vosk engine.
        if (!prefs.picovoiceKey.isNullOrBlank() && prefs.hasKeyword) {
            try { return PorcupineWakeEngine(this, prefs, { onWake() }, onErr) }
            catch (e: PorcupineException) { Log.w("SamiWake", "Porcupine failed, falling back to Vosk", e) }
        }
        WakeBus.status("Loading wake word model…")
        return try {
            VoskWakeEngine.create(this, prefs.sensitivity, { scope.launch { onWake() } }, onErr)
        } catch (e: Exception) {
            Log.e("SamiWake", "Vosk init failed", e); WakeBus.status("Couldn't load wake model: ${e.message}"); null
        }
    }

    private fun resume() {
        val p = engine ?: return
        if (listening) return
        runCatching { p.start(); listening = true; WakeBus.status("Listening for “Hey Sami” · ${p.name}") }
            .onFailure { Log.e("SamiWake", "start", it); WakeBus.status("Mic unavailable: ${it.message}") }
    }

    private fun pause() {
        if (!listening) return
        runCatching { engine?.stop() }
        listening = false
        WakeBus.status("Paused while Sami is talking")
    }

    private fun onWake() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < 2500) return // debounce repeated detections
        lastTrigger = now
        Log.i("SamiWake", "wake word detected")
        pause() // free the mic for speech recognition immediately
        WakeBus.assistantActive.value = true
        // Preferred: the assistant session (works over other apps and on the lock screen).
        if (SamiVoiceInteractionService.trigger()) return
        // Fallback: direct launch (Android may block this from the background).
        val intent = Intent(this, AssistOverlayActivity::class.java).setAction(MainActivity.ACTION_LISTEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { startActivity(intent) }
        // Always post a tap-to-talk notification in case the launch was blocked.
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_sami).setContentTitle("Sami heard you")
                .setContentText("Tap to talk. Set Sami as your digital assistant for instant pop-up.")
                .setContentIntent(PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).setTimeoutAfter(15_000).build())
        // If nothing picked up the session, resume listening shortly.
        scope.launch { kotlinx.coroutines.delay(8_000); if (WakeBus.assistantActive.value && !MainActivity.visible && !AssistOverlayActivity.visible) WakeBus.assistantActive.value = false }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Sami listens for “Hey Sami” on-device"
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val talk = PendingIntent.getActivity(this, 3, Intent(this, AssistOverlayActivity::class.java).setAction(MainActivity.ACTION_LISTEN), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sami).setContentTitle("Sami").setContentText(text)
            .setOngoing(true).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Talk", talk).addAction(0, "Stop listening", stop).build()
    }

    override fun onDestroy() {
        runCatching { engine?.release() }
        engine = null; listening = false
        scope.cancel()
        WakeBus.status("Off")
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }
}

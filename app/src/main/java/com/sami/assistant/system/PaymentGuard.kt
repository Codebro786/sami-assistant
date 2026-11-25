package com.sami.assistant.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sami.assistant.R

/**
 * UPI and banking apps refuse to work while any third-party accessibility service is on (anti-fraud).
 * Sami therefore switches its own screen control OFF as soon as one of these apps opens, and back ON after.
 * - Off: the service calls disableSelf() (always allowed).
 * - On again: needs WRITE_SECURE_SETTINGS (granted once by the owner over adb); otherwise a tap-to-restore notification.
 */
object PaymentGuard {
    /** Payment / banking / investing apps on the owner's phone + common Indian ones. */
    val protectedApps = setOf(
        "net.one97.paytm", "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "in.org.npci.upiapp",
        "com.dreamplug.androidapp", "com.nextbillion.groww", "com.Version1", "com.piepay", "com.mobikwik_new",
        "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.snapwork.hdfc", "com.csam.icici.bank.imobile", "com.axis.mobile",
        "com.msf.kbank.mobile", "com.naviapp", "money.jupiter", "com.epifi.paisa", "com.whatsapp.w4b.payments",
        "com.zerodha.kite3", "com.upstox.pro", "com.angelbroking.angelwealth",
    )

    private const val PREF = "a11y_paused_for_payment"
    private fun prefs(c: Context) = c.getSharedPreferences("sami", Context.MODE_PRIVATE)
    fun isPaused(c: Context) = prefs(c).getBoolean(PREF, false)
    private fun component(c: Context) = ComponentName(c, SamiAccessibilityService::class.java).flattenToString()
    fun canSelfRestore(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Called by the accessibility service when a protected app comes to the front. */
    fun pauseFor(svc: SamiAccessibilityService, pkg: String) {
        Log.i("SamiGuard", "payment app $pkg opened → screen control off")
        prefs(svc).edit().putBoolean(PREF, true).apply()
        svc.disableSelf()
    }

    /** Re-enable once the owner has left payment apps. Safe to call often. */
    fun restoreIfLeft(c: Context, foreground: String?) {
        if (!isPaused(c) || foreground == null || foreground in protectedApps) return
        if (canSelfRestore(c)) {
            val cr = c.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = component(c)
            if (!cur.split(':').contains(me)) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (cur.split(':').filter { it.isNotBlank() } + me).joinToString(":"))
                Settings.Secure.putString(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            }
            prefs(c).edit().putBoolean(PREF, false).apply()
            Log.i("SamiGuard", "left payment apps → screen control restored")
        } else {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("guard", "Screen control", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(c, 77, detailsIntent(c), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(77, NotificationCompat.Builder(c, "guard").setSmallIcon(R.drawable.ic_stat_sami)
                .setContentTitle("Sami screen control is off").setContentText("It switched off so your UPI app works. Tap the Sami screen control tile in Quick Settings to turn it back on.")
                .setContentIntent(open).setAutoCancel(true).build())
            prefs(c).edit().putBoolean(PREF, false).apply() // notify once per payment
        }
    }

    /** Opens Sami's own accessibility page (one toggle), not the long Accessibility list. */
    fun detailsIntent(c: Context): Intent =
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(c, SamiAccessibilityService::class.java).flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openDetails(c: Context) {
        runCatching { c.startActivity(detailsIntent(c)) }.onFailure {
            Log.w("SamiGuard", "details page refused: ${it.javaClass.simpleName}: ${it.message}")
            c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Manual switch (Settings tile). Returns false when Android needs the owner to flip it in Accessibility settings. */
    fun setEnabled(c: Context, on: Boolean): Boolean {
        if (!on) {
            SamiAccessibilityService.instance?.let { it.disableSelf(); return true }
            if (!canSelfRestore(c)) return true // already off
        }
        if (!canSelfRestore(c)) return false
        val cr = c.contentResolver
        val me = component(c)
        val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter { it.isNotBlank() && it != me }
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (if (on) list + me else list).joinToString(":"))
        if (on) Settings.Secure.putString(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        prefs(c).edit().putBoolean(PREF, false).apply()
        return true
    }

    /** Most recent foreground app from usage stats (usage access is granted on the owner's phone). */
    fun foregroundApp(c: Context): String? = runCatching {
        val usm = c.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 15_000, now); val e = UsageEvents.Event(); var last: String? = null
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName }
        last
    }.getOrNull()
}

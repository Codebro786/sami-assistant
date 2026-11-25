package com.sami.assistant.system

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** One capability the user can switch on, with an honest explanation of why it's needed. */
data class Capability(
    val id: String,
    val title: String,
    val why: String,
    val granted: Boolean,
    /** Runtime permission to request, or null if it is granted through a settings screen. */
    val runtimePermission: String? = null,
    val settingsIntent: Intent? = null,
)

object PermissionCenter {

    fun a11yEnabled(context: Context): Boolean {
        if (SamiAccessibilityService.isRunning) return true
        val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, SamiAccessibilityService::class.java).flattenToString()
        return flat.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun isAssistant(context: Context): Boolean {
        val rm = context.getSystemService(RoleManager::class.java)
        return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    fun notifListener(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(c.packageName)
    }

    fun usageAccess(c: Context): Boolean {
        val ops = c.getSystemService(android.app.AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun all(c: Context): List<Capability> = listOf(
        Capability("mic", "Microphone", "To hear your commands. Sami never records in secret; the green indicator shows whenever the mic is in use.",
            has(c, Manifest.permission.RECORD_AUDIO), runtimePermission = Manifest.permission.RECORD_AUDIO),
        Capability("notif", "Notifications", "Shows the wake-word status notification and tap-to-talk prompts.",
            has(c, Manifest.permission.POST_NOTIFICATIONS), runtimePermission = Manifest.permission.POST_NOTIFICATIONS),
        Capability("contacts", "Contacts", "To find people for calls, WhatsApp and payments (“pay Rahul 20 rs”). Read only on the phone; never uploaded.",
            has(c, Manifest.permission.READ_CONTACTS), runtimePermission = Manifest.permission.READ_CONTACTS),
        Capability("phone", "Phone calls", "Lets Sami place a call after you confirm. Without it Sami opens the dialer instead.",
            has(c, Manifest.permission.CALL_PHONE), runtimePermission = Manifest.permission.CALL_PHONE),
        Capability("photos", "Photos", "To open photos by date (“photos from 25 Dec”) and your latest screenshot. Photos never leave the phone.",
            has(c, Manifest.permission.READ_MEDIA_IMAGES), runtimePermission = Manifest.permission.READ_MEDIA_IMAGES),
        Capability("files", "All files access", "To find documents like your Aadhaar or academic calendar anywhere on the phone (by name or by reading them on-device).",
            android.os.Environment.isExternalStorageManager(), settingsIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${c.packageName}"))),
        Capability("notiflistener", "Notification access", "For “What did I miss?”. Kept in memory only; sent to the AI only if you allow it below.",
            notifListener(c), settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
        Capability("usage", "Usage access", "For “how much Instagram today?” and screen-time answers.",
            usageAccess(c), settingsIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
        Capability("exact", "Exact reminders", "So reminders fire on the minute instead of a few minutes late.",
            c.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms(),
            settingsIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}"))),
        Capability("assistant", "Default digital assistant", "Lets Sami open from the assist gesture or power-button hold, over any app and on the lock screen. Open Digital assistant app › Default digital assistant app › Sami.",
            isAssistant(c), settingsIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)),
        Capability("a11y", "Screen control", "Lets Sami press Back, open recents, scroll and tap items you name. Only used when you ask; nothing is recorded. Find “Sami screen control” under Downloaded apps.",
            a11yEnabled(c), settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
        Capability("write", "Modify system settings", "Needed to change screen brightness.",
            Settings.System.canWrite(c), settingsIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${c.packageName}"))),
        Capability("dnd", "Do Not Disturb access", "Lets Sami turn Do Not Disturb on and off.",
            c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted,
            settingsIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
        Capability("battery", "Unrestricted battery", "OxygenOS freezes background apps after a few seconds. Wake word only survives if Sami’s battery use is Unrestricted. Tap Allow; then also check App info › Battery usage › Allow background activity.",
            c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName),
            settingsIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))),
    )
}

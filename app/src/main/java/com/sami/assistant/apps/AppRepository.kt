package com.sami.assistant.apps

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Lists launchable apps (requires the MAIN/LAUNCHER <queries> entry in the manifest). */
class AppRepository(private val context: Context) {
    private val mutex = Mutex()
    private var cache: List<AppEntry>? = null
    private var cachedAt = 0L

    suspend fun launchableApps(): List<AppEntry> = mutex.withLock {
        val now = System.currentTimeMillis()
        cache?.takeIf { now - cachedAt < 60_000 }?.let { return it }
        val list = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
        cache = list; cachedAt = now
        list
    }

    fun launchIntent(packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

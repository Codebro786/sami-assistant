package com.sami.assistant.system

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sami.assistant.R
import com.sami.assistant.apps.AppMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Contacts
// ---------------------------------------------------------------------------------------------
data class Contact(val key: String, val name: String, val phones: List<String>)

class ContactsRepo(private val context: Context, private val aliases: () -> Map<String, String> = { emptyMap() }) {
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun all(): List<Contact> = withContext(Dispatchers.IO) {
        if (!granted()) return@withContext emptyList()
        val map = LinkedHashMap<String, Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue; val name = c.getString(1) ?: continue; val num = c.getString(2) ?: continue
                val prev = map[key]
                map[key] = Contact(key, name, ((prev?.phones ?: emptyList()) + num).distinctBy { normalize(it) })
            }
        }
        map.values.toList()
    }

    sealed interface Match {
        data class One(val contact: Contact) : Match
        data class Many(val options: List<Contact>) : Match
        data object None : Match
        data object NoPermission : Match
    }

    suspend fun find(spoken: String): Match {
        if (!granted()) return Match.NoPermission
        val all = all()
        // Nicknames first ("mom" → "Mom Mobile"), matched exactly against the saved contact name.
        val key = spoken.lowercase().trim().removePrefix("my ").removeSuffix(" ji").trim()
        aliases()[key]?.let { target -> all.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { return Match.One(it) } }
        return ContactMatcher.match(spoken, all)
    }

    companion object {
        /** Digits only, Indian numbers as 91XXXXXXXXXX (wa.me format). */
        fun normalize(n: String): String {
            val d = n.filter { it.isDigit() }
            return when {
                d.length == 10 -> "91$d"
                d.length == 11 && d.startsWith("0") -> "91${d.drop(1)}"
                else -> d
            }
        }
    }
}

/** Pure fuzzy contact matching (unit-tested). Ambiguity is reported, never guessed. */
object ContactMatcher {
    private fun norm(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N} ]"""), " ").replace(Regex("""\s+"""), " ").trim()
    private val honorifics = Regex("""\s*(ji|bhai|bhaiya|sir|madam|didi|di|bro)$""")
    private val relations = mapOf("mom" to listOf("mom", "mummy", "maa", "mother", "mumma", "ma"), "mummy" to listOf("mom", "mummy", "maa", "mother", "mumma"),
        "maa" to listOf("mom", "mummy", "maa", "mother"), "dad" to listOf("dad", "papa", "father", "daddy", "pappa"), "papa" to listOf("dad", "papa", "father", "daddy"))

    fun match(spoken: String, all: List<Contact>): ContactsRepo.Match {
        val q = norm(spoken).replace(honorifics, "")
        if (q.isEmpty() || all.isEmpty()) return ContactsRepo.Match.None
        val wanted = relations[q] ?: listOf(q)
        fun pick(list: List<Contact>) = when (list.size) { 0 -> null; 1 -> ContactsRepo.Match.One(list[0]); else -> ContactsRepo.Match.Many(list.take(4)) }
        pick(all.filter { c -> wanted.any { norm(c.name) == it } })?.let { return it }
        pick(all.filter { c -> wanted.any { w -> norm(c.name).split(" ").firstOrNull() == w } })?.let { return it }
        pick(all.filter { c -> wanted.any { w -> norm(c.name).split(" ").contains(w) } })?.let { return it }
        pick(all.filter { c -> wanted.any { w -> w.length >= 3 && norm(c.name).startsWith(w) } })?.let { return it }
        val best = all.map { it to AppMatcher.distance(norm(it.name).split(" ").first(), q) }.filter { it.second <= if (q.length <= 4) 1 else 2 }
        return pick(best.sortedBy { it.second }.let { l -> l.filter { it.second == l.firstOrNull()?.second }.map { it.first } }) ?: ContactsRepo.Match.None
    }
}

// ---------------------------------------------------------------------------------------------
// Notifications ("what did I miss?") — kept only in memory, never written to disk.
// ---------------------------------------------------------------------------------------------
data class SeenNotification(val app: String, val pkg: String, val title: String, val text: String, val time: Long)

object NotificationStore {
    private val items = ArrayDeque<SeenNotification>()
    @Synchronized fun add(n: SeenNotification) {
        items.removeAll { it.pkg == n.pkg && it.title == n.title && it.text == n.text }
        items.addLast(n); while (items.size > 400) items.removeFirst()
    }
    @Synchronized fun recent(sinceMillis: Long): List<SeenNotification> = items.filter { it.time >= sinceMillis }
    @Synchronized fun clear() = items.clear()
}

class SamiNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var connected = false
        @Volatile private var instance: SamiNotificationListener? = null
        /** Pull notifications currently in the shade (covers ones posted before Sami started). */
        fun seed() { instance?.let { l -> runCatching { l.activeNotifications?.forEach { l.onNotificationPosted(it) } } } }
    }
    override fun onListenerConnected() { connected = true; instance = this; seed() }
    override fun onListenerDisconnected() { connected = false; instance = null }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category in setOf(Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE, Notification.CATEGORY_PROGRESS)) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        NotificationStore.add(SeenNotification(label, sbn.packageName, title.take(80), text.take(300), sbn.postTime))
    }
}

// ---------------------------------------------------------------------------------------------
// Reminders
// ---------------------------------------------------------------------------------------------
object Reminders {
    const val CHANNEL = "reminders"
    fun schedule(context: Context, text: String, at: Long): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, (at / 1000).toInt(), Intent(context, ReminderReceiver::class.java).putExtra("text", text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return runCatching {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // may fire a few minutes late
            true
        }.getOrDefault(false)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(Reminders.CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.notify((System.currentTimeMillis() % 100000).toInt(), NotificationCompat.Builder(context, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sami).setContentTitle("Reminder").setContentText(intent.getStringExtra("text") ?: "Reminder")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true).build())
    }
}

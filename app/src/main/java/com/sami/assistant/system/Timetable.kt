package com.sami.assistant.system

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sami.assistant.R
import com.sami.assistant.SamiApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Example weekly timetable + venue floors, with class reminders. Edit `slots` and `floorOf` for your own college. */
object Timetable {
    data class Slot(val day: DayOfWeek, val start: LocalTime, val end: LocalTime, val subject: String, val faculty: String?, val venue: String)

    val slots = listOf(
        Slot(DayOfWeek.MONDAY, t(10), t(11), "Data Structures", "Prof A", "CR-10"),
        Slot(DayOfWeek.MONDAY, t(14), t(16), "Programming Lab", "Prof B", "CL-10"),
        Slot(DayOfWeek.WEDNESDAY, t(9), t(10), "Mathematics", "Prof C", "CR-7"),
        Slot(DayOfWeek.THURSDAY, t(11), t(12), "Physics", "Prof D", "LT-3"),
        Slot(DayOfWeek.FRIDAY, t(13), t(14), "Electronics", "Prof E", "CR-8"),
    )
    private fun t(h: Int) = LocalTime.of(h, 0)

    /** Example building layout: room code → floor. */
    fun floorOf(venue: String): String {
        val v = venue.uppercase().replace(" ", "")
        val n = Regex("""\d+""").find(v)?.value?.toIntOrNull()
        return when {
            v.startsWith("CR") && n in listOf(1, 2) -> "Lower Level -1"
            v.startsWith("CR") && n in listOf(3, 4) -> "Ground floor"
            v.startsWith("CR") && n in 5..10 -> "First floor"
            v.startsWith("CR") && n in listOf(11, 12) -> "Second floor"
            v.startsWith("CR") && n in 16..19 -> "Fourth floor"
            v.startsWith("LT") && n in listOf(1, 2) -> "Ground floor"
            v.startsWith("LT") && n == 3 -> "First floor"
            v.startsWith("CL") && n in listOf(7, 8) -> "Ground floor"
            v.startsWith("CL") && n in 9..11 -> "First floor"
            v.startsWith("CL") && n == 1 -> "Second floor"
            v.startsWith("CL") && n in listOf(3, 4, 5, 6, 12) -> "Third floor"
            else -> "Academic Block"
        }
    }

    fun describe(s: Slot) = "${s.subject} in ${s.venue}, ${floorOf(s.venue)}" + (s.faculty?.let { " with $it" } ?: "")

    fun on(day: DayOfWeek) = slots.filter { it.day == day }.sortedBy { it.start }

    fun next(now: LocalDateTime = LocalDateTime.now()): Pair<Slot, LocalDateTime>? =
        slots.map { s -> s to occurrence(s, now) }.minByOrNull { it.second }

    private fun occurrence(s: Slot, now: LocalDateTime): LocalDateTime {
        var d = now.toLocalDate().with(TemporalAdjusters.nextOrSame(s.day)).atTime(s.start)
        if (!d.isAfter(now)) d = d.plusWeeks(1)
        return d
    }

    // ---- reminders -------------------------------------------------------------------------------
    private const val CHANNEL = "classes"
    const val LEAD_MIN = 10L

    /** Schedules the next reminder for every class (each alarm re-schedules itself a week later when it fires). */
    fun scheduleAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now()
        slots.forEachIndexed { i, s ->
            var at = occurrence(s, now).minusMinutes(LEAD_MIN)
            if (!at.isAfter(now)) at = at.plusWeeks(1)
            val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = PendingIntent.getBroadcast(context, 9000 + i, Intent(context, ClassReminderReceiver::class.java).putExtra("slot", i),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            runCatching {
                // OxygenOS stretches ordinary exact alarms by up to 1 h for battery; alarm-clock alarms are never delayed.
                if (am.canScheduleExactAlarms()) am.setAlarmClock(AlarmManager.AlarmClockInfo(ms,
                    PendingIntent.getActivity(context, 9500, Intent(context, com.sami.assistant.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)), pi)
                else { android.util.Log.w("SamiClasses", "exact alarms not allowed — reminder may be late"); am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi) }
            }
        }
    }

    fun notify(context: Context, s: Slot) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Class reminders", NotificationManager.IMPORTANCE_HIGH))
        val text = "${s.subject} at ${s.start.format(DateTimeFormatter.ofPattern("h:mm a"))} — ${s.venue}, ${floorOf(s.venue)}" + (s.faculty?.let { " · $it" } ?: "")
        nm.notify(9100 + slots.indexOf(s), NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_sami)
            .setContentTitle("Class in $LEAD_MIN minutes").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true)
            // Disappears on its own 10 min after the class starts (shown LEAD_MIN before start).
            .setTimeoutAfter((LEAD_MIN + 10) * 60_000).build())
    }
}

class ClassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = Timetable.slots.getOrNull(intent.getIntExtra("slot", -1)) ?: return
        Timetable.notify(context, s)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Also say it out loud (Sami plays its own audio, so Android doesn't mute it).
                val c = (context.applicationContext as SamiApp).container
                c.speechOut.speak("Your ${s.subject} class starts in ${Timetable.LEAD_MIN} minutes, in ${s.venue}, ${Timetable.floorOf(s.venue)}.")
            } finally { Timetable.scheduleAll(context); pending.finish() }
        }
    }
}

/** Re-create class reminders after a reboot, an app update or a time change. */
class ReschedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Timetable.scheduleAll(context)
}

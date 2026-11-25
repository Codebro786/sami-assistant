package com.sami.assistant.commands

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month

/** Pure English/Hinglish time and date parsing. All functions take "now" so they are testable. */
object TimeParser {

    private val months = mapOf(
        "jan" to Month.JANUARY, "january" to Month.JANUARY, "feb" to Month.FEBRUARY, "february" to Month.FEBRUARY,
        "mar" to Month.MARCH, "march" to Month.MARCH, "apr" to Month.APRIL, "april" to Month.APRIL, "may" to Month.MAY,
        "jun" to Month.JUNE, "june" to Month.JUNE, "jul" to Month.JULY, "july" to Month.JULY, "aug" to Month.AUGUST,
        "august" to Month.AUGUST, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER, "september" to Month.SEPTEMBER,
        "oct" to Month.OCTOBER, "october" to Month.OCTOBER, "nov" to Month.NOVEMBER, "november" to Month.NOVEMBER,
        "dec" to Month.DECEMBER, "december" to Month.DECEMBER,
    )
    private val monthAlt = months.keys.sortedByDescending { it.length }.joinToString("|")

    /** "7", "7:30", "7.30 pm", "subah 7 baje", "shaam 6", "19:45" → time of day. */
    fun timeOfDay(s: String): LocalTime? {
        val m = Regex("""(?:^|\s)(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.|baje|o'?clock)?(?=\s|$)""").findAll(s)
            .firstOrNull { it.groupValues[3].isNotEmpty() || it.groupValues[2].isNotEmpty() || Regex("""(at|pe|par|for|ka|ki|ke)\s+${it.groupValues[1]}""").containsMatchIn(s) }
            ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        if (h > 23 || min > 59) return null
        val suffix = m.groupValues[3]
        val pm = suffix.startsWith("p") || Regex("""(shaam|sham|evening|raat|night|dopahar|afternoon)""").containsMatchIn(s)
        val am = suffix.startsWith("a") || Regex("""(subah|morning)""").containsMatchIn(s)
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        // "raat 12" means midnight
        if (Regex("""raat""").containsMatchIn(s) && m.groupValues[1].toInt() == 12) h = 0
        return LocalTime.of(h, min)
    }

    /** "in 10 minutes", "20 min", "2 ghante", "1 hour 30 minutes" → seconds. */
    fun duration(s: String): Int? {
        var total = 0; var found = false
        Regex("""(\d+)\s*(hours?|hrs?|ghante?|ghanta)""").find(s)?.let { total += it.groupValues[1].toInt() * 3600; found = true }
        Regex("""(\d+)\s*(minutes?|mins?|minat|minute)""").find(s)?.let { total += it.groupValues[1].toInt() * 60; found = true }
        Regex("""(\d+)\s*(seconds?|secs?|second)""").find(s)?.let { total += it.groupValues[1].toInt(); found = true }
        if (!found && Regex("""(half an hour|aadha ghanta|adha ghanta)""").containsMatchIn(s)) { total = 1800; found = true }
        return if (found && total > 0) total else null
    }

    /** "25 dec 2025", "dec 25", "25/12/2025", "yesterday", "today", "kal" (past) → date. */
    fun date(s: String, today: LocalDate): LocalDate? {
        when {
            Regex("""(^|\s)(today|aaj)('s)?(\s|$)""").containsMatchIn(s) -> return today
            Regex("""(^|\s)(yesterday|kal)('s)?(\s|$)""").containsMatchIn(s) -> return today.minusDays(1)
            Regex("""day before yesterday|parso""").containsMatchIn(s) -> return today.minusDays(2)
        }
        Regex("""(\d{1,2})\s*(?:st|nd|rd|th)?\s+(?:of\s+)?($monthAlt)(?:\s+(\d{4}))?""").find(s)?.let { m ->
            return safeDate(m.groupValues[3].toIntOrNull(), months[m.groupValues[2]]!!, m.groupValues[1].toInt(), today)
        }
        Regex("""($monthAlt)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?""").find(s)?.let { m ->
            return safeDate(m.groupValues[3].toIntOrNull(), months[m.groupValues[1]]!!, m.groupValues[2].toInt(), today)
        }
        Regex("""(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?""").find(s)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            val mo = m.groupValues[2].toInt(); if (mo !in 1..12) return null
            return safeDate(y, Month.of(mo), m.groupValues[1].toInt(), today)
        }
        return null
    }

    /** Without a year, pick the most recent past occurrence (photos are in the past). */
    private fun safeDate(year: Int?, month: Month, day: Int, today: LocalDate): LocalDate? = runCatching {
        if (year != null) LocalDate.of(year, month, day)
        else LocalDate.of(today.year, month, day).let { if (it.isAfter(today)) it.minusYears(1) else it }
    }.getOrNull()

    /** Next occurrence of [t] after [now] (today if still ahead, else tomorrow; "kal"/"tomorrow" forces tomorrow). */
    fun nextOccurrence(t: LocalTime, now: LocalDateTime, s: String): LocalDateTime {
        val tomorrow = Regex("""(^|\s)(tomorrow|kal)(\s|$)""").containsMatchIn(s)
        var at = now.toLocalDate().atTime(t)
        if (tomorrow) at = at.plusDays(1) else if (!at.isAfter(now)) at = at.plusDays(1)
        return at
    }
}

package com.sami.assistant.commands

import com.sami.assistant.commands.Command.*
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Local (no-AI) grammar for personal-assistant commands in English and Hinglish.
 * Input is already normalised by [CommandParser.normalize]. Returns null if not confident.
 */
object PersonalParser {

    private fun has(s: String, vararg w: String) = w.any { Regex("""(^|\s)${Regex.escape(it)}($|\s)""").containsMatchIn(s) }
    private fun clean(x: String) = x.trim().removePrefix("the ").removePrefix("to ").trim()

    fun parse(s: String, now: LocalDateTime): Command? =
        samiSetting(s) ?: classQuery(s) ?: photoOf(s) ?: openFile(s) ?: memory(s) ?: routine(s) ?: battery(s) ?: mail(s) ?: messages(s) ?: research(s) ?: missed(s) ?: screenTime(s) ?: screenAsk(s) ?: pay(s) ?: whatsapp(s) ?: call(s) ?:
        reminder(s, now) ?: alarm(s) ?: timer(s) ?: photos(s, now) ?: youtubeLatest(s) ?: youtubePlay(s) ?: instagram(s) ?: shop(s) ?: music(s)

    private fun onOff(s: String): String? = when {
        has(s, "off", "disable", "band", "stop", "deactivate", "mat") -> "off"
        has(s, "on", "enable", "chalu", "start", "activate") -> "on"
        else -> null
    }

    private fun classQuery(s: String): Command? {
        Regex("""(?:where is|where's|kahan hai|kaha hai|kidhar hai|location of)\s+((?:cr|lt|cl)\s*-?\s*\d{1,2})""").find(s)?.let { return ClassQuery(ClassKind.WHERE, venue = it.groupValues[1].replace(" ", "").uppercase()) }
        Regex("""^((?:cr|lt|cl)\s*-?\s*\d{1,2})\s+(?:kahan|kaha|kidhar|where)""").find(s)?.let { return ClassQuery(ClassKind.WHERE, venue = it.groupValues[1].replace(" ", "").uppercase()) }
        if (!has(s, "class", "classes", "lecture", "lectures", "lab", "timetable", "time table", "schedule", "period")) return null
        if (has(s, "next", "agli", "agla", "upcoming")) return ClassQuery(ClassKind.NEXT)
        val off = when { has(s, "tomorrow", "kal") -> 1; has(s, "today", "aaj") -> 0; else -> null }
        if (off != null || has(s, "what", "which", "kya", "kaunsi", "konsi", "my")) return ClassQuery(ClassKind.DAY, dayOffset = off ?: 0)
        return null
    }

    private val herWords = setOf("her", "gf", "girlfriend", "baby", "jaan", "my girl", "my gf")
    private fun photoOf(s: String): Command? {
        val m = Regex("""(?:photo|photos|pic|pics|picture|pictures|selfie|tasveer)s?\s+(?:of|with|ki|ka)\s+(.+)$""").find(s)
            ?: Regex("""^(?:show|open|dikhao)?\s*(.+?)\s+(?:ki|ka|wali|vali)\s+(?:photo|pic|tasveer)\s*(?:dikhao|kholo)?$""").find(s) ?: return null
        val who = m.groupValues[1].replace(Regex("""\b(a|any|random|together|dono|ki|ka|the|from gallery)\b"""), " ").replace(Regex("""\s+"""), " ").trim()
        val people = mutableSetOf<String>()
        if (Regex("""(^|\s)(me|mine|myself|us|hum|meri|mera|main)(\s|$)""").containsMatchIn(who) || who.contains("me and") || who.contains("and me")) people += "me"
        if (herWords.any { Regex("""(^|\s)${Regex.escape(it)}(\s|$)""").containsMatchIn(who) } || who.contains("us")) people += "her"
        if (who.contains("us") || who.contains("hum dono") || who.contains("both of us")) { people += "me"; people += "her" }
        return if (people.isNotEmpty()) PhotoOf(people) else null
    }

    private val docWords = listOf("aadhaar", "aadhar", "adhar", "adhaar", "pan card", "calendar", "calender", "certificate", "marksheet", "mark sheet",
        "result", "ticket", "receipt", "invoice", "licence", "license", "passport", "resume", "cv", "timetable", "time table", "syllabus",
        "admit card", "id card", "fee", "bill", "report", "notes", "assignment", "offer letter", "pdf", "document", "file")
    private fun openFile(s: String): Command? {
        val m = Regex("""^(?:open|show|find|search|dikhao|kholo|nikalo)\s+(?:me\s+)?(?:my\s+)?(.+?)(?:\s+(?:kholo|dikhao|nikalo))?$""").find(s)
            ?: Regex("""^(?:my\s+)?(.+?)\s+(?:kholo|dikhao|nikalo|open karo)$""").find(s) ?: return null
        val q = m.groupValues[1].trim()
        if (docWords.none { w -> Regex("""(^|\s)${Regex.escape(w)}(s)?($|\s)""").containsMatchIn(q) }) return null
        if (Regex("""(settings|app$)""").containsMatchIn(q)) return null
        return OpenFile(q)
    }

    private fun samiSetting(s: String): Command? {
        val k = SettingKey::class.java
        fun set(key: SettingKey, v: String) = SamiSetting(key, v)
        // speech speed / pitch
        if (Regex("""(talk|speak|bolo|bol)\s+(faster|fast|jaldi|tez)|speak quicker|speed up (your )?voice|(jaldi|tez|fast)\s+(bolo|bol|baat karo)""").containsMatchIn(s)) return set(SettingKey.SPEECH_RATE, "+")
        if (Regex("""(talk|speak|bolo|bol)\s+(slower|slow|dheere|aaram se)|slow down (your )?voice|(dheere|dhire|slow|aaram se)\s+(bolo|bol|baat karo)""").containsMatchIn(s)) return set(SettingKey.SPEECH_RATE, "-")
        Regex("""(?:speech|speaking|voice) (?:speed|rate) (?:to )?(\d{1,3})""").find(s)?.let { return set(SettingKey.SPEECH_RATE, it.groupValues[1]) }
        if (Regex("""(higher|deeper|lower) (voice|pitch)|(voice|pitch) (higher|lower|deeper)""").containsMatchIn(s))
            return set(SettingKey.PITCH, if (has(s, "higher")) "+" else "-")
        // reply language
        Regex("""(?:reply|talk|speak|answer|baat karo|bolo)\s+(?:to me\s+)?(?:in|mein|me)\s+(english|hinglish|hindi)""").find(s)?.let {
            return set(SettingKey.REPLY_LANGUAGE, if (it.groupValues[1] == "english") "english" else "hinglish")
        }
        if (s.contains("match my language") || s.contains("reply in my language")) return set(SettingKey.REPLY_LANGUAGE, "auto")
        // wake / sleep times
        Regex("""(?:my )?(?:wake ?up|wake) time (?:is |to )?(.+)$""").find(s)?.let { m -> TimeParser.timeOfDay("at " + m.groupValues[1])?.let { return set(SettingKey.WAKE_TIME, "%02d:%02d".format(it.hour, it.minute)) } }
        Regex("""(?:my )?(?:sleep|bed) ?time (?:is |to )?(.+)$""").find(s)?.let { m -> TimeParser.timeOfDay("at " + m.groupValues[1])?.let { return set(SettingKey.SLEEP_TIME, "%02d:%02d".format(it.hour, it.minute)) } }
        // sensitivity
        Regex("""(?:wake(?: word)? )?sensitivity (?:to )?(\d{1,3})""").find(s)?.let { return set(SettingKey.SENSITIVITY, it.groupValues[1]) }
        if (has(s, "sensitivity")) return set(SettingKey.SENSITIVITY, if (has(s, "increase", "higher", "more", "badhao", "up")) "+" else "-")
        // toggles
        val v = onOff(s) ?: return null
        return when {
            s.contains("night pause") || s.contains("sleep pause") || s.contains("pause at night") || s.contains("sleep mode") -> set(SettingKey.SLEEP_PAUSE, v)
            s.contains("wake word") || s.contains("hey sami") || s.contains("wakeword") || s.contains("listening for") -> set(SettingKey.WAKE, v)
            s.contains("continuous") -> set(SettingKey.CONTINUOUS, v)
            s.contains("notification summar") || s.contains("ai notification") -> set(SettingKey.AI_NOTIFICATIONS, v)
            s.contains("screen reading") || s.contains("read the screen") && has(s, "ai") -> set(SettingKey.AI_SCREEN, v)
            else -> null
        }.also { k.hashCode() }
    }

    private fun memory(s: String): Command? {
        if (s in setOf("forget everything", "forget all", "sab bhool jao", "clear memory")) return ForgetAll
        Regex("""^(?:remember|yaad rakhna|yaad rakho|note that|keep in mind)(?:\s+that|\s+ki)?\s+(.{3,})$""").find(s)?.let { return Remember(it.groupValues[1]) }
        Regex("""^(.{3,}?)\s+yaad rakhna$""").find(s)?.let { return Remember(it.groupValues[1]) }
        return null
    }

    private fun routine(s: String): Command? = when {
        s in setOf("good night", "goodnight", "gn", "shubh ratri", "so raha hoon", "so rahi hoon", "sone ja raha hoon", "night mode", "bedtime") ||
            s.startsWith("good night ") -> Routine(RoutineName.GOOD_NIGHT)
        s in setOf("good morning", "gm", "suprabhat", "uth gaya", "uth gayi", "i'm up", "im up") || s.startsWith("good morning ") -> Routine(RoutineName.GOOD_MORNING)
        else -> null
    }

    private fun battery(s: String): Command? {
        if (!has(s, "battery", "charge", "charging", "बैटरी")) return null
        if (has(s, "saver", "settings", "setting", "optimization")) return null
        return if (has(s, "kitni", "kitna", "level", "percent", "percentage", "how much", "status", "left", "bachi", "hai", "what's", "whats", "is"))
            Battery else null
    }

    private fun research(s: String): Command? {
        Regex("""^(?:please\s+)?(?:check|search|look up|research|find out|google|dhundo|pata karo)\s+(?:online|on the internet|on internet|the web|on the web|on reddit|internet (?:pe|par))\s+(?:if|whether|about|for|ki|kya)?\s*(.+)$""").find(s)?.let { return Research(clean(it.groupValues[1])) }
        Regex("""^(?:research|google|find out|look up)\s+(?:if|whether|about|for)?\s*(.{6,})$""").find(s)?.let { return Research(clean(it.groupValues[1])) }
        Regex("""^(.{6,}?)\s+(?:online|internet (?:pe|par)|google (?:pe|par)|reddit (?:pe|par))\s+(?:check karo|dekho|search karo|pata karo|check kar)$""").find(s)?.let { return Research(clean(it.groupValues[1])) }
        Regex("""^(?:check|search)\s+(.{6,}?)\s+(?:online|on the internet|on reddit|on google)$""").find(s)?.let { return Research(clean(it.groupValues[1])) }
        return null
    }

    private val chatApps = mapOf("whatsapp" to "whatsapp", "insta" to "instagram", "instagram" to "instagram", "telegram" to "telegram", "snapchat" to "snapchat", "discord" to "discord")
    private fun messages(s: String): Command? {
        val app = chatApps.entries.firstOrNull { has(s, it.key) }?.value
        Regex("""^(?:what did|what has|has)\s+(.+?)\s+(?:say|said|sent|send|text|texted|message|messaged)(?:\s+(?:me|on \w+))?$""").find(s)?.let { return Messages(app, clean(it.groupValues[1])) }
        Regex("""^(.+?)\s+ne\s+(?:kya\s+)?(?:kaha|bola|bheja|message kiya|likha)(?:\s+\w+)?$""").find(s)?.let { return Messages(app, clean(it.groupValues[1])) }
        Regex("""^(?:any|koi)?\s*(?:new\s+|naya\s+)?(?:messages?|msgs?|texts?|dm|dms)\s+(?:from|se)\s+(.+?)(?:\s+on \w+)?$""").find(s)?.let { return Messages(app, clean(it.groupValues[1])) }
        val asks = Regex("""(any|new|unread|read|check|show|koi|naye|kya aaya|kya aaye|what are)""").containsMatchIn(s)
        if (app != null && asks && has(s, "message", "messages", "msg", "msgs", "chats", "dm", "dms", "aaya", "aaye", "texts")) return Messages(app, null)
        if (s in setOf("any messages", "any new messages", "read my messages", "koi message aaya", "check my messages", "show my messages")) return Messages(null, null)
        return null
    }

    private fun mail(s: String): Command? {
        if (!has(s, "mail", "mails", "email", "emails", "gmail", "inbox", "e-mail")) return null
        if (has(s, "send", "write", "compose", "bhejo", "likho")) return null
        Regex("""(?:mail|email|mails|emails)\s+(?:from|se|by)\s+(.+?)(?:\s+(?:today|this week|yesterday))?$""").find(s)?.let { return Mail("from:(${clean(it.groupValues[1])})") }
        Regex("""(?:mail|email)s?\s+(?:about|regarding|related to)\s+(.+)$""").find(s)?.let { return Mail(clean(it.groupValues[1])) }
        return Mail(null) // "any important mail?", "check my gmail", "koi important mail aaya"
    }

    private fun missed(s: String): Command? =
        if (s in setOf("what did i miss", "what i missed", "anything new", "any notifications", "any new notifications", "read my notifications",
                "read notifications", "notification summary", "summarize notifications", "summarize my notifications", "kya miss kiya",
                "maine kya miss kiya", "koi message aaya", "koi notification", "notifications padho", "any messages", "check my notifications"))
            Missed else null

    private fun screenTime(s: String): Command? {
        if (has(s, "screen time", "screentime")) return ScreenTime(null)
        Regex("""(?:how much time|how long)\s+(?:did i spend|have i spent|i spent|i used|on)?\s*(?:on\s+)?(.+?)(?:\s+today)?$""").find(s)?.let {
            return ScreenTime(clean(it.groupValues[1]).removeSuffix(" app"))
        }
        Regex("""^(?:aaj\s+)?(.+?)\s+(?:pe|par)\s+kitna\s+time""").find(s)?.let { return ScreenTime(clean(it.groupValues[1])) }
        return null
    }

    private fun screenAsk(s: String): Command? {
        val triggers = listOf("what's this", "whats this", "what is this", "what is on this page", "summarize this", "summarise this",
            "summarize the page", "summarize this page", "summarize the screen", "summarize my screen", "explain this", "explain the screen",
            "ye kya hai", "yeh kya hai", "is page ko samjhao", "isko samjhao", "translate this", "is this true", "who is this", "ye kaun hai",
            "help me reply", "what should i reply", "read this chat", "summarize this chat", "what are they saying", "ye chat padho",
            "is chat mein kya likha hai", "what is this chat about", "summarize this conversation")
        return if (triggers.any { s == it || s.startsWith("$it ") }) ScreenAsk(s) else null
    }

    // ---- money ------------------------------------------------------------------------------
    private const val AMT = """(?:rs\.?|rupees?|rupaye|rupay|rupiya|₹|inr)?\s*(\d{1,6}(?:\.\d{1,2})?)\s*(?:rs\.?|rupees?|rupaye|rupay|rupiya|₹|bucks|inr)?"""
    private fun pay(s: String): Command? {
        val pats = listOf(
            Regex("""^(?:pay|send|transfer|bhejo)\s+$AMT\s+(?:to|ko)\s+(.+?)(?:\s+(?:on|via|through|using)\s+\w+)?$""") to Pair(2, 1),
            Regex("""^(?:pay|send|transfer)\s+(.+?)\s+$AMT(?:\s+(?:on|via|through|using)\s+\w+)?$""") to Pair(1, 2),
            Regex("""^(.+?)\s+ko\s+$AMT\s+(?:bhejo|bhej do|de do|dedo|pay karo|transfer karo|send karo)$""") to Pair(1, 2),
        )
        for ((re, idx) in pats) {
            val m = re.find(s) ?: continue
            val name = clean(m.groupValues[idx.first]); val amt = m.groupValues[idx.second].toDoubleOrNull() ?: continue
            if (name.isBlank() || amt <= 0 || name.any { it.isDigit() }) continue
            return Pay(name, amt)
        }
        return null
    }

    private fun call(s: String): Command? {
        if (has(s, "call log", "call history", "recent calls", "volume", "awaaz", "ringtone")) return null
        Regex("""^(?:call|phone|dial|ring|video call)\s+(.+)$""").find(s)?.let { return Call(clean(it.groupValues[1]).removeSuffix(" now")) }
        Regex("""^(.+?)\s+ko\s+(?:call|phone)\s+(?:karo|lagao|kar do|laga do|milao)$""").find(s)?.let { return Call(clean(it.groupValues[1])) }
        return null
    }

    /** "my gf" / "to her" → the person part, kept short so nicknames resolve. */
    private fun person(x: String) = clean(x).removePrefix("my ").removeSuffix(" on whatsapp").removeSuffix(" please").trim()

    private fun whatsapp(s: String): Command? {
        // Casual messaging defaults to WhatsApp (how the user texts).
        Regex("""^(?:say|send|wish)\s+(.+?)\s+to\s+(.+?)(?:\s+on whatsapp)?$""").find(s)?.let {
            val text = it.groupValues[1]; val who = person(it.groupValues[2])
            if (who.isNotBlank() && text.isNotBlank() && !text.startsWith("money") && !text.any { c -> c.isDigit() && text.length < 4 }) return WhatsApp(who, text)
        }
        Regex("""^tell\s+(.+?)\s+(?:that\s+)?(.+)$""").find(s)?.let { m ->
            val who = person(m.groupValues[1].removeSuffix(" that"))
            if (who.split(" ").size <= 3 && m.groupValues[2].length >= 2) return WhatsApp(who, m.groupValues[2])
        }
        Regex("""^(.+?)\s+ko\s+(?:bolo|bol do|bata do|batao|keh do|kaho)\s+(?:ki\s+)?(.+)$""").find(s)?.let { return WhatsApp(person(it.groupValues[1]), it.groupValues[2]) }
        Regex("""^(.+?)\s+ko\s+(.+?)\s+(?:bol do|bolo|keh do|bhej do|bhejo)$""").find(s)?.let {
            if (!Regex("""\d""").containsMatchIn(it.groupValues[2])) return WhatsApp(person(it.groupValues[1]), it.groupValues[2])
        }
        Regex("""^(?:message|text|msg|whatsapp|send a message to|send message to)\s+(.+?)\s+(?:on whatsapp\s+)?(?:saying|that|ki|:|-)\s+(.+)$""").find(s)?.let {
            return WhatsApp(clean(it.groupValues[1]).removeSuffix(" on whatsapp"), it.groupValues[2])
        }
        Regex("""^(.+?)\s+ko\s+(?:whatsapp|message|msg)\s+(?:karo|bhejo|kar do)\s+(?:ki\s+)?(.+)$""").find(s)?.let { return WhatsApp(clean(it.groupValues[1]), it.groupValues[2]) }
        Regex("""^(?:open\s+)?(?:whatsapp\s+)?chat\s+(?:with|of)\s+(.+?)(?:\s+on whatsapp)?$""").find(s)?.let { return WhatsApp(clean(it.groupValues[1]), null) }
        Regex("""^(.+?)\s+(?:ki|ka)\s+(?:whatsapp\s+)?chat\s+kholo$""").find(s)?.let { return WhatsApp(clean(it.groupValues[1]), null) }
        return null
    }

    // ---- clock ------------------------------------------------------------------------------
    private fun alarm(s: String): Command? {
        if (!has(s, "alarm", "wake me", "utha dena", "uthana", "jaga dena", "अलार्म") || has(s, "volume", "awaaz", "sound")) return null
        val t = TimeParser.timeOfDay(s) ?: return null
        return Alarm(t.hour, t.minute)
    }

    private fun timer(s: String): Command? {
        if (!has(s, "timer", "countdown", "टाइमर")) return null
        return TimeParser.duration(s)?.let { Timer(it) }
    }

    private fun reminder(s: String, now: LocalDateTime): Command? {
        val isRemind = s.startsWith("remind me") || has(s, "yaad dilana", "yaad dila dena", "reminder")
        if (!isRemind) return null
        var text = s.replace(Regex("""^remind me\s*(?:to|that|about)?\s*"""), "")
            .replace(Regex("""(set a |set )?reminder\s*(?:to|for)?\s*"""), "")
            .replace(Regex("""\s*(?:mujhe\s+)?yaad (?:dila dena|dilana)\s*(?:ki)?\s*"""), " ")
        val at: LocalDateTime = TimeParser.duration(s)?.let { now.plusSeconds(it.toLong()) }
            ?: TimeParser.timeOfDay(s)?.let { TimeParser.nextOccurrence(it, now, s) }
            ?: return null
        text = text.replace(Regex("""\b(in|after)\s+\d+\s*\w+|\d+\s*(minutes?|mins?|hours?|ghante?)\s*(baad|mein|me)?"""), " ")
            .replace(Regex("""(at|pe|par)?\s*\d{1,2}([:.]\d{2})?\s*(am|pm|baje)?"""), " ")
            .replace(Regex("""\b(tomorrow|today|kal|aaj|subah|shaam|raat|morning|evening|tonight)\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        if (text.isBlank()) text = "Reminder"
        return Reminder(text, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    // ---- media / photos ---------------------------------------------------------------------
    private fun photos(s: String, now: LocalDateTime): Command? {
        if (has(s, "screenshot", "screenshots") && has(s, "last", "latest", "recent", "my", "show", "open", "pichla", "aakhri")) return Photos(screenshot = true)
        if (!has(s, "photo", "photos", "pic", "pics", "pictures", "picture", "images", "gallery", "tasveer", "tasveerein", "फोटो")) return null
        val d = TimeParser.date(s, now.toLocalDate()) ?: return null
        return Photos(epochDay = d.toEpochDay())
    }

    private val latestRe = listOf(
        Regex("""^(?:open|play|show|watch|chalao|dikhao)?\s*(?:the\s+)?(?:latest|newest|new|recent|last)\s+(?:video|upload|vid)\s+(?:of|from|by)\s+(.+?)(?:\s+(?:on|in)\s+(?:youtube|yt))?$"""),
        Regex("""^(?:open|play|show|watch)?\s*(.+?)(?:'s|s')?\s+(?:latest|newest|new|recent|last)\s+(?:video|upload|vid)(?:\s+(?:on|in)\s+(?:youtube|yt))?$"""),
        Regex("""^(?:youtube|yt)\s+(?:pe|par)?\s*(.+?)\s+(?:ka|ki)\s+(?:latest|naya|nayi|new)\s+(?:video|upload)(?:\s+\w+)?$"""),
        Regex("""^(.+?)\s+(?:ka|ki)\s+(?:latest|naya|nayi|new)\s+(?:video|upload)(?:\s+(?:chalao|kholo|dikhao|lagao))?(?:\s+(?:youtube|yt)\s+(?:pe|par))?$"""),
    )
    private fun youtubeLatest(s: String): Command? {
        for (re in latestRe) re.find(s)?.let { m ->
            val ch = clean(m.groupValues[1]).removePrefix("youtube ").removePrefix("yt ").removeSuffix(" channel").trim()
            if (ch.isNotBlank() && ch !in setOf("a", "the", "my")) return YouTubeLatest(ch)
        }
        return null
    }

    private fun youtubePlay(s: String): Command? {
        Regex("""^(?:play|watch|show|open|chalao|lagao|dikhao)\s+(?:the\s+)?(?:video\s+(?:of\s+)?)?(.+?)\s+(?:video|vid|trailer)(?:\s+(?:on|in)\s+(?:youtube|yt))?$""").find(s)?.let { return YouTubePlay(clean(it.groupValues[1]) + if (s.contains("trailer")) " trailer" else "") }
        Regex("""^(?:play|watch)\s+(.+?)\s+(?:on|in)\s+(?:youtube|yt)$""").find(s)?.let { return YouTubePlay(clean(it.groupValues[1])) }
        Regex("""^(?:youtube|yt)\s+(?:pe|par)\s+(.+?)\s+(?:chalao|lagao|play karo|dikhao)$""").find(s)?.let { return YouTubePlay(clean(it.groupValues[1])) }
        Regex("""^(.+?)\s+(?:ka|ki|ke)\s+(?:video|trailer)\s+(?:chalao|lagao|dikhao|play karo)$""").find(s)?.let { return YouTubePlay(clean(it.groupValues[1])) }
        return null
    }

    private fun instagram(s: String): Command? {
        Regex("""^(?:open|show)?\s*(.+?)(?:'s\s+(?:instagram|insta)(?:\s+profile|\s+account|\s+page)?|\s+(?:instagram|insta)\s+(?:profile|account|page))$""").find(s)?.let {
            val u = clean(it.groupValues[1]); if (u !in setOf("open", "my", "")) return InstagramProfile(u.replace(" ", ""))
        }
        Regex("""^(?:open\s+)?(?:instagram|insta)\s+(?:profile|account|page)\s+(?:of\s+)?(.+)$""").find(s)?.let { return InstagramProfile(clean(it.groupValues[1]).replace(" ", "")) }
        Regex("""^(?:instagram|insta)\s+(?:pe|par)\s+(.+?)\s+(?:kholo|dikhao|ka profile kholo)$""").find(s)?.let { return InstagramProfile(clean(it.groupValues[1]).replace(" ", "")) }
        return null
    }

    private val stores = mapOf("flipkart" to Store.FLIPKART, "amazon" to Store.AMAZON, "myntra" to Store.MYNTRA, "blinkit" to Store.BLINKIT)
    private fun shop(s: String): Command? {
        val alt = stores.keys.joinToString("|")
        Regex("""^(?:search|find|look for|search for|order|buy|dhundo)\s+(.+?)\s+(?:on|in|from)\s+($alt)$""").find(s)?.let { return ShopSearch(stores[it.groupValues[2]]!!, clean(it.groupValues[1])) }
        Regex("""^($alt)\s+(?:pe|par|on)\s+(.+?)\s+(?:search karo|dhundo|search|dikhao|order karo)$""").find(s)?.let { return ShopSearch(stores[it.groupValues[1]]!!, clean(it.groupValues[2])) }
        Regex("""^(?:search\s+)?($alt)\s+(?:for\s+)(.+)$""").find(s)?.let { return ShopSearch(stores[it.groupValues[1]]!!, clean(it.groupValues[2])) }
        return null
    }

    private val genericPlay = setOf("music", "song", "songs", "something", "gaana", "gana", "kuch", "the music", "my music", "a song", "any song")
    private fun music(s: String): Command? {
        val app = when {
            has(s, "spotify") -> MusicApp.SPOTIFY
            s.contains("youtube music") || s.contains("yt music") -> MusicApp.YT_MUSIC
            else -> MusicApp.DEFAULT
        }
        val stripped = s.replace(Regex("""\s+(?:on|in|par|pe)\s+(?:spotify|youtube music|yt music)"""), "").replace(Regex("""^(?:spotify|yt music|youtube music)\s+(?:pe|par)\s+"""), "")
        if (has(stripped, "youtube", "yt", "video")) return null
        val q = Regex("""^(?:play|bajao|sunao|put on|start)\s+(?:the\s+)?(?:song\s+)?(.+?)(?:\s+song|\s+gaana)?$""").find(stripped)?.groupValues?.get(1)
            ?: Regex("""^(.+?)\s+(?:song|gaana|gana)?\s*(?:bajao|chalao|lagao|sunao|play karo|laga do|baja do)$""").find(stripped)?.groupValues?.get(1)
            ?: return null
        val query = clean(q)
        if (query.isBlank() || query in genericPlay || query.startsWith("next") || query.startsWith("previous")) return null
        return PlayMusic(query, app)
    }
}

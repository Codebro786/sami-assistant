package com.sami.assistant.commands

import com.sami.assistant.commands.Command.Direction
import com.sami.assistant.commands.Command.MediaOp
import com.sami.assistant.commands.Command.SettingsPage
import com.sami.assistant.commands.Command.Stream

/**
 * Path A: deterministic, offline, zero-latency parser for common commands.
 * Handles English, Hindi (Devanagari) and Hinglish (romanised Hindi) phrasing.
 * Anything it cannot parse confidently returns [Command.Unknown] rather than guessing.
 */
object CommandParser {

    // Lookarounds instead of \b: Java's \b misbehaves around Devanagari combining marks.
    private val wakePrefix = Regex("""^(?:(?:hey|hi|ok|okay|hello|हे|ओके)\s+)?(?:sami|samy|sammy|saami|सामी|सैमी)(?=\s|$)\s*""")
    private val politeness = Regex("""(?<=^|\s)(please|plz|pls|kindly|zara|jara|कृपया|ज़रा|जरा)(?=\s|$)""")

    /** Command words worth auto-correcting. Names are never in here, so contacts aren't "fixed". */
    private val vocab = listOf(
        "open", "launch", "start", "close", "play", "pause", "resume", "next", "previous", "stop", "skip", "song", "songs", "music", "video",
        "volume", "increase", "decrease", "louder", "quieter", "mute", "unmute", "brightness", "flashlight", "torch",
        "bluetooth", "wifi", "settings", "screenshot", "scroll", "down", "back", "home", "recent", "notifications",
        "call", "message", "whatsapp", "instagram", "youtube", "spotify", "gallery", "photos", "camera", "chrome", "netflix",
        "send", "pay", "rupees", "alarm", "timer", "remind", "reminder", "tomorrow", "today", "yesterday", "minutes", "hours",
        "search", "check", "online", "research", "battery", "remember", "forget", "latest", "trailer", "email", "mail", "important",
        "disturb", "morning", "night", "please", "turn", "set", "what", "show", "tell", "messages", "screen", "time",
        "kholo", "chalao", "bajao", "lagao", "badhao", "karo", "awaaz", "dikhao", "bhejo",
    )
    private val vocabSet = vocab.toHashSet()

    /** Real words that look like typos of commands — never "corrected". */
    private val realWords = ("that this than then them they there their these those when with wish what where which while main mein cell long " +
        "press hold photo photos picture from have here hello help will make made take like more much very just only also into over some " +
        "send sent same name come came done good keep want went word work year your about after again could would should " +
        "january february march april june july august september october november december monday tuesday wednesday thursday friday " +
        "saturday sunday mom mummy papa dad bhai didi yaar kya hai hain nahi haan theek kuch abhi phir aur mera meri tera teri " +
        "spend spent spends plan plans pain past post cool call calls card cart part plays paid pays told tall till fill feel fall full").split(" ").toHashSet()

    /** Optimal-string-alignment distance: a swap of two neighbouring letters ("opne") costs 1. */
    private fun osa(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** Fix typos in command words only; anything that's a real word or a name is left as typed. */
    /** Common misspellings too far off for automatic correction. */
    private val knownFixes = mapOf("flashlite" to "flashlight", "flashlit" to "flashlight", "flaslight" to "flashlight", "torcch" to "torch",
        "watsapp" to "whatsapp", "whatsup" to "whatsapp", "whatapp" to "whatsapp", "wattsapp" to "whatsapp", "watsap" to "whatsapp",
        "utube" to "youtube", "youtub" to "youtube", "yutube" to "youtube", "spotfy" to "spotify", "instgram" to "instagram",
        "brigtness" to "brightness", "brightnees" to "brightness", "volum" to "volume", "remaind" to "remind", "alaram" to "alarm", "allarm" to "alarm")

    fun correctTypos(s: String): String = s.split(" ").joinToString(" ") { w0 ->
        val w = knownFixes[w0] ?: w0
        if (w != w0) return@joinToString w
        if (w.length < 3 || w in vocabSet || w in realWords || w.any { it.isDigit() } || w.any { it.code > 127 } || w.contains('\'')) return@joinToString w
        if (w.length == 3) return@joinToString vocab.firstOrNull { it.length == 4 && osa(w, it) == 1 && it.startsWith(w.take(2)) } ?: w
        val max = if (w.length >= 8) 2 else 1
        val best = vocab.filter { kotlin.math.abs(it.length - w.length) <= max && it[0] == w[0] }
            .map { it to osa(w, it) }.filter { it.second <= max }
        // Only correct when exactly one command word is that close — ambiguous typos go to the AI instead.
        val top = best.minOfOrNull { it.second } ?: return@joinToString w
        best.filter { it.second == top }.singleOrNull()?.first ?: w
    }

    fun normalize(raw: String): String {
        var s = raw.lowercase().trim()
        s = s.replace(Regex("""[?!,।]+|\.(?!\w)"""), " ")
        s = wakePrefix.replace(s, "")
        s = politeness.replace(s, " ")
        return correctTypos(s.replace(Regex("""\s+"""), " ").trim())
    }

    fun parse(raw: String, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Command {
        val s = normalize(raw)
        if (s.isEmpty()) return Command.Unknown(raw)

        stop(s)?.let { return it }
        // Bare media phrases ("play", "play music") win over song search.
        media(s)?.takeIf { (it as Command.Media).op == Command.MediaOp.PLAY }?.let { return it }
        PersonalParser.parse(s, now)?.let { return it }
        flashlight(s)?.let { return it }
        brightnessAndDnd(s)?.let { return it }
        volume(s)?.let { return it }
        youtube(s)?.let { return it }
        media(s)?.let { return it }
        navigation(s)?.let { return it }
        settings(s)?.let { return it }
        openApp(s)?.let { return it }
        return Command.Unknown(raw)
    }

    // ---- individual grammars -------------------------------------------------------------

    private fun has(s: String, vararg words: String) = words.any { Regex("""(^|\s)${Regex.escape(it)}($|\s)""").containsMatchIn(s) }

    private fun stop(s: String): Command? =
        if (s in setOf("stop", "cancel", "never mind", "nevermind", "chup", "chup karo", "ruko", "bas", "रुको", "बस", "चुप"))
            Command.Stop else null

    private fun flashlight(s: String): Command? {
        if (!has(s, "flashlight", "torch", "flash light", "टॉर्च", "फ्लैशलाइट")) return null
        val off = has(s, "off", "band", "bandh", "disable", "बंद", "बुझाओ") || s.contains("turn off")
        val on = has(s, "on", "chalu", "jalao", "enable", "start", "चालू", "जलाओ", "ऑन")
        return when {
            off -> Command.Flashlight(false)
            on -> Command.Flashlight(true)
            else -> Command.Flashlight(true)
        }
    }

    private fun volume(s: String): Command? {
        val isVolume = has(s, "volume", "awaaz", "awaz", "aawaz", "sound", "आवाज़", "आवाज", "वॉल्यूम") ||
            has(s, "mute", "unmute", "louder", "quieter", "softer")
        if (!isVolume) return null
        val stream = when {
            has(s, "ring", "ringer", "ringtone", "रिंग") -> Stream.RING
            has(s, "alarm", "अलार्म") -> Stream.ALARM
            else -> Stream.MEDIA
        }
        Regex("""(\d{1,3})\s*(%|percent|pratishat)""").find(s)?.let {
            return Command.VolumeSet(it.groupValues[1].toInt().coerceIn(0, 100), stream)
        }
        Regex("""(?:to|par|pe)\s+(\d{1,3})\b""").find(s)?.let {
            return Command.VolumeSet(it.groupValues[1].toInt().coerceIn(0, 100), stream)
        }
        val steps = if (has(s, "lot", "bahut", "zyada", "jyada", "बहुत")) 3 else 1
        val dir = when {
            has(s, "unmute") -> Direction.UNMUTE
            has(s, "mute", "म्यूट") -> Direction.MUTE
            has(s, "max", "maximum", "full", "poora", "पूरा") -> Direction.MAX
            has(s, "up", "increase", "raise", "louder", "badhao", "badha", "badhaao", "tez", "jyada", "zyada", "बढ़ाओ", "बढ़ा", "तेज़", "तेज") -> Direction.UP
            has(s, "down", "decrease", "lower", "reduce", "quieter", "softer", "kam", "ghatao", "dheere", "dheema", "कम", "घटाओ", "धीमा") -> Direction.DOWN
            else -> return null
        }
        return Command.Volume(dir, stream, steps)
    }

    private fun youtube(s: String): Command? {
        if (!has(s, "youtube", "यूट्यूब")) return null
        // "search X on youtube", "play X on youtube", "youtube pe X chalao", "youtube par X search karo"
        val patterns = listOf(
            Regex("""^(?:search|play|find|look up|dhundo|chalao|lagao)\s+(?:for\s+)?(.+?)\s+(?:on|in|par|pe)\s+youtube$"""),
            Regex("""^youtube\s+(?:pe|par|on|me|mein)\s+(.+?)\s+(?:search|dhundo|chalao|lagao|play|bajao)(?:\s+karo)?$"""),
            Regex("""^(?:search\s+)?youtube\s+(?:for\s+)?(.+)$"""),
            Regex("""^(.+?)\s+youtube\s+(?:pe|par)\s+(?:search\s+karo|dhundo|chalao|lagao)$"""),
        )
        for (p in patterns) {
            val q = p.find(s)?.groupValues?.get(1)?.trim()
            if (!q.isNullOrBlank() && q !in setOf("open", "kholo", "khol do")) return Command.YouTubeSearch(q)
        }
        return null // plain "open youtube" falls through to openApp
    }

    private fun media(s: String): Command? {
        val op = when {
            has(s, "next song", "next track", "skip", "agla gaana", "agla gana", "agla", "अगला") ||
                s == "next" || s.startsWith("play next") -> MediaOp.NEXT
            has(s, "previous", "previous song", "last song", "pichla", "pichhla", "पिछला") -> MediaOp.PREVIOUS
            has(s, "pause", "pause music", "roko", "रोको") || s.contains("gaana band") || s.contains("music band") ||
                s.contains("stop music") || s.contains("stop the music") -> MediaOp.PAUSE
            s.contains("fast forward") || s.contains("forward") && has(s, "seconds", "song", "aage") -> MediaOp.FORWARD
            has(s, "rewind") || s.contains("go back") && has(s, "seconds") -> MediaOp.REWIND
            s in setOf("play", "resume", "play music", "resume music", "play song", "gaana chalao", "gana chalao",
                "music chalao", "gaana bajao", "music play karo", "chalao", "चलाओ", "गाना चलाओ") -> MediaOp.PLAY
            s in setOf("play pause", "toggle music") -> MediaOp.TOGGLE
            else -> return null
        }
        return Command.Media(op)
    }

    private fun navigation(s: String): Command? = when {
        s in setOf("home", "go home", "go to home", "home screen", "go to home screen", "home pe jao", "home par jao", "होम") -> Command.Home
        s in setOf("back", "go back", "peeche", "peeche jao", "wapas", "wapas jao", "back jao", "पीछे", "वापस") -> Command.Global(Command.GlobalOp.BACK)
        has(s, "recents", "recent apps", "recent") -> Command.Global(Command.GlobalOp.RECENTS)
        has(s, "screenshot", "screen shot", "स्क्रीनशॉट") -> Command.Global(Command.GlobalOp.SCREENSHOT)
        has(s, "quick settings") -> Command.Global(Command.GlobalOp.QUICK_SETTINGS)
        has(s, "notification panel", "notifications", "notification") && has(s, "open", "show", "kholo", "dikhao", "pull down") ->
            Command.Global(Command.GlobalOp.NOTIFICATIONS)
        s in setOf("lock", "lock screen", "lock the phone", "lock phone", "lock the screen", "phone lock karo", "screen lock karo") -> Command.Global(Command.GlobalOp.LOCK)
        else -> screen(s)
    }

    private val ordinals = mapOf(
        "first" to 1, "1st" to 1, "pehla" to 1, "pehle" to 1, "second" to 2, "2nd" to 2, "doosra" to 2, "dusra" to 2, "dusre" to 2,
        "third" to 3, "3rd" to 3, "teesra" to 3, "tisra" to 3, "teesre" to 3, "fourth" to 4, "4th" to 4, "chautha" to 4,
        "fifth" to 5, "5th" to 5, "panchva" to 5, "last" to -1, "aakhri" to -1,
    )
    private val ordinalRe = Regex("""(?:^|\s)(${ordinals.keys.joinToString("|")})\s+(?:result|item|video|option|one|link|post|song|wala|vala|entry)""")
    private val tapPrefix = Regex("""^(long press|press and hold|hold|tap|click|press|select|choose)\s+(?:on\s+)?(?:the\s+)?(.+)$""")
    private val tapSuffix = Regex("""^(.+?)\s+(?:pe|par|ko)\s+(click|tap|long press)\s*(?:karo|kar do|kijiye)?$""")
    private val tapDabao = Regex("""^(.+?)\s+(?:dabao|daba do|dabaiye)$""")
    private val targetNoise = Regex("""\s+(button|icon|option|tab|link|wala|vala)$""")

    private fun screen(s: String): Command? {
        // Read screen
        if (s in setOf("read the screen", "read screen", "what's on the screen", "whats on the screen", "what's on my screen",
                "whats on my screen", "what is on the screen", "what is on my screen", "screen padho", "screen pe kya hai", "screen par kya hai"))
            return Command.ReadScreen

        // Scroll / swipe
        val isScroll = has(s, "scroll", "swipe", "neeche", "niche", "upar", "ऊपर", "नीचे")
        if (isScroll && (has(s, "scroll", "swipe") || has(s, "karo", "jao", "kar"))) {
            val dir = when {
                has(s, "up", "upar", "ऊपर", "top") -> Command.ScrollDir.UP
                has(s, "left", "baaye", "baayein") -> Command.ScrollDir.LEFT
                has(s, "right", "daaye", "daayein") -> Command.ScrollDir.RIGHT
                else -> Command.ScrollDir.DOWN
            }
            return Command.Scroll(dir)
        }

        // Tap / click
        val m = tapPrefix.find(s)
        val (raw, long) = when {
            m != null -> m.groupValues[2] to m.groupValues[1].let { it.startsWith("long") || it.contains("hold") }
            else -> tapSuffix.find(s)?.let { it.groupValues[1] to (it.groupValues[2] == "long press") }
                ?: tapDabao.find(s)?.let { it.groupValues[1] to false }
                ?: return null
        }
        ordinalRe.find(raw)?.let { return Command.Tap(ordinal = ordinals[it.groupValues[1]], longPress = long) }
        val target = targetNoise.replace(raw.trim().removePrefix("the ").removePrefix("on "), "").trim()
        return if (target.isBlank()) null else Command.Tap(target = target, longPress = long)
    }

    private fun brightnessAndDnd(s: String): Command? {
        if (has(s, "dnd", "do not disturb", "disturb")) {
            val off = has(s, "off", "band", "disable", "hatao", "बंद")
            return Command.DoNotDisturb(!off)
        }
        if (!has(s, "brightness", "roshni", "chamak", "ब्राइटनेस")) return null
        Regex("""(\d{1,3})\s*(%|percent)?""").find(s)?.let { return Command.Brightness(percent = it.groupValues[1].toInt().coerceIn(0, 100)) }
        val dir = when {
            has(s, "max", "maximum", "full", "poori") -> Direction.MAX
            has(s, "min", "minimum", "lowest") -> Direction.MUTE
            has(s, "up", "increase", "badhao", "badha", "zyada", "jyada", "tez", "raise", "बढ़ाओ") -> Direction.UP
            has(s, "down", "decrease", "kam", "lower", "reduce", "ghatao", "dim", "कम") -> Direction.DOWN
            else -> return Command.OpenSettings(SettingsPage.DISPLAY)
        }
        return Command.Brightness(direction = dir)
    }

    private fun settings(s: String): Command? {
        val wantsOpen = has(s, "open", "kholo", "khol", "show", "go to", "dikhao", "खोलो") || s.endsWith("settings")
        if (!has(s, "settings", "setting", "सेटिंग", "सेटिंग्स") || !wantsOpen) {
            // "turn on wifi / bluetooth" can only be done via settings panel on Android 10+
            if (has(s, "wifi", "wi-fi", "वाईफाई")) return Command.OpenSettings(SettingsPage.WIFI)
            if (has(s, "bluetooth", "ब्लूटूथ")) return Command.OpenSettings(SettingsPage.BLUETOOTH)
            return null
        }
        val page = when {
            has(s, "wifi", "wi-fi", "internet") -> SettingsPage.WIFI
            has(s, "bluetooth") -> SettingsPage.BLUETOOTH
            has(s, "display", "brightness", "screen") -> SettingsPage.DISPLAY
            has(s, "sound", "volume") -> SettingsPage.SOUND
            has(s, "battery") -> SettingsPage.BATTERY
            has(s, "app", "apps", "applications") -> SettingsPage.APPS
            has(s, "assistant") -> SettingsPage.ASSISTANT
            has(s, "accessibility") -> SettingsPage.ACCESSIBILITY
            else -> SettingsPage.MAIN
        }
        return Command.OpenSettings(page)
    }

    private val openVerbsPrefix = Regex("""^(?:open|launch|start|run|show me|show|go to)\s+(?:the\s+)?(.+?)(?:\s+app(?:lication)?)?$""")
    private val openVerbsSuffix = Regex("""^(.+?)(?:\s+app)?\s+(?:kholo|khol do|kholiye|open karo|open kar do|open kar|chalu karo|start karo|launch karo|खोलो|खोल दो|ओपन करो)$""")

    private fun openApp(s: String): Command? {
        val name = openVerbsPrefix.find(s)?.groupValues?.get(1)
            ?: openVerbsSuffix.find(s)?.groupValues?.get(1)
            ?: return null
        val clean = name.trim().removeSuffix(" app").trim()
        return if (clean.isBlank()) null else Command.OpenApp(clean)
    }
}

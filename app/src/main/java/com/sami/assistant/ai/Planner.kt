package com.sami.assistant.ai

import com.sami.assistant.commands.Command
import com.sami.assistant.commands.Command.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Path B. Used only when the local parser can't understand a request.
 * The model may only answer with JSON naming actions from a fixed catalogue; [PlanParser]
 * validates every field and drops anything unknown. Generated text is never executed.
 */
class Planner(private val groq: GroqClient) {

    data class Plan(val say: String, val commands: List<Command>, val raw: String = "")

    data class Context(
        val facts: List<String>, val apps: List<String>, val aliases: Map<String, String>, val learned: Map<String, String>,
        val lastContact: String?, val hinglish: Boolean, val recentActions: List<String>,
    )

    suspend fun plan(utterance: String, history: List<Pair<Boolean, String>>, ctx: Context, now: LocalDateTime): Plan {
        val msgs = buildList {
            add(GroqClient.Msg("system", systemPrompt(ctx, now)))
            history.takeLast(8).forEach { (user, text) -> add(GroqClient.Msg(if (user) "user" else "assistant", text)) }
            add(GroqClient.Msg("user", utterance))
        }
        val raw = groq.chat(msgs, json = true, maxTokens = 900)
        return PlanParser.parse(raw, now).copy(raw = raw)
    }

    /** Free-form answer about provided content (screen text, notifications, web evidence). */
    suspend fun answer(instruction: String, content: String, maxTokens: Int = 700): String =
        groq.chat(listOf(
            GroqClient.Msg("system", "You are Sami, a concise, sharp voice assistant (think JARVIS). Reply in at most 4 short spoken sentences, " +
                "in the user's language (English, or Hinglish in Latin script). No markdown, no lists, no URLs."),
            GroqClient.Msg("user", "$instruction\n\n---\n${content.take(14000)}"),
        ), json = false, maxTokens = maxTokens).trim()

    /** Deep research: searches several sites (incl. Reddit threads), reads the top pages, then the AI synthesises a verdict. */
    suspend fun research(query: String, hinglish: Boolean): String {
        val (evidence, sites) = WebSearch.gather(query, deep = true)
        if (evidence.isBlank()) throw GroqClient.AiException("I couldn't reach the web right now.", offline = true)
        val instr = "The user asked: \"$query\". Using ONLY the web evidence below (search results, Reddit threads and page text), give a spoken verdict " +
            "in 3-4 short sentences${if (hinglish) " in natural Hinglish (Latin script)" else ""}: what most sources say, how common or serious it is, " +
            "and what the user should do. Mention if sources disagree or evidence is thin. End with 'Sources: ' and the 2-4 most useful site names."
        return answer(instr, evidence, maxTokens = 1200).ifBlank { "I found ${sites.size} sources but couldn't summarise them." }
    }

    /** Quick live answer (news, scores, prices) from search snippets. */
    suspend fun webAnswer(query: String, hinglish: Boolean): String {
        val (evidence, _) = WebSearch.gather(query, deep = false)
        if (evidence.isBlank()) throw GroqClient.AiException("I couldn't reach the web right now.", offline = true)
        return answer("Answer \"$query\" in at most 2 short spoken sentences${if (hinglish) " in Hinglish" else ""} using these fresh search results. " +
            "Give the concrete fact (score, price, date). If the results don't contain it, say so.", evidence, maxTokens = 600)
    }

    private fun systemPrompt(ctx: Context, now: LocalDateTime) = """
You are Sami — think JARVIS: a calm, quick-witted, highly capable personal assistant living on the user's Android phone (OnePlus 12, India).
You understand intent, not just words: infer what the user obviously means, use conversation context and pronouns ("her", "usko", "that one", "again"),
and pick the most sensible action instead of refusing. Be brief and confident; a touch of dry wit is welcome, never chatty.
Language: reply in ${if (ctx.hinglish) "natural Hinglish written in Latin script (e.g. \"Ho gaya, volume 40 kar diya\")" else "English"} — match the user's latest message.
Current local time: ${now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm"))}.
Reply ONLY with a JSON object: {"say": "<short spoken reply, same language style as user, max 2 sentences>", "actions": [ ... ]}
"actions" may be empty (for questions/chat). Use at most 4 actions, executed in order. Allowed actions (use exact keys):
{"action":"open_app","app":"<app name>"}
{"action":"youtube_search","query":"..."}  {"action":"youtube_play","query":"<video to play>"}            {"action":"youtube_latest","channel":"<channel name>"}
{"action":"play_music","query":"<song/artist>","app":"spotify|yt_music|default"}
{"action":"media","op":"play|pause|next|previous"}
{"action":"volume","direction":"up|down|mute|max"}   {"action":"volume_set","percent":0-100}
{"action":"brightness","percent":0-100}              {"action":"flashlight","on":true|false}
{"action":"dnd","on":true|false}                     {"action":"settings","page":"main|wifi|bluetooth|display|sound|battery|apps"}
{"action":"home"} {"action":"back"} {"action":"scroll","dir":"up|down"} {"action":"tap","target":"<visible text>"} {"action":"tap","ordinal":<n>}
{"action":"pay","name":"<contact name>","amount":<rupees>}
{"action":"call","name":"<contact name>"}
{"action":"whatsapp","name":"<contact name>","text":"<message or null>"}
{"action":"alarm","time":"HH:mm"}                     {"action":"timer","seconds":<n>}
{"action":"reminder","text":"...","time":"YYYY-MM-DDTHH:mm"}
{"action":"photos","date":"YYYY-MM-DD"}               {"action":"screenshot_latest"}
{"action":"instagram_profile","username":"<handle without @>"}
{"action":"shop_search","store":"flipkart|amazon|myntra|blinkit","query":"..."}
{"action":"sami_setting","key":"wake|sleep_pause|sensitivity|continuous|speech_rate|pitch|reply_language|wake_time|sleep_time","value":"on|off|+|-|<0-100>|english|hinglish|auto|HH:mm"}
{"action":"open_file","query":"<document words, e.g. aadhaar card, academic calendar>"}  {"action":"photo_of","people":["me","her"]}
{"action":"class_next"} {"action":"class_day","offset":0|1} {"action":"where_room","venue":"CR-8"}
{"action":"remember","fact":"..."}                    {"action":"missed_notifications"}
{"action":"routine","name":"good_night|good_morning"}{"action":"battery"} {"action":"screen_time","app":"<optional>"}
{"action":"web_search","query":"<self-contained search query>"}   ← quick facts
{"action":"research","query":"<question>"}   ← deeper multi-source checks: "is X a known problem", reviews, comparisons, "check reddit"
{"action":"messages","app":"whatsapp|instagram|null","person":"<name or null>"}   {"action":"mail","query":"<gmail search or null>"}   ← for anything current: news, scores, weather, prices, releases, "who is", "latest"
Rules: never invent contacts' UPI IDs or phone numbers; payments/calls/messages are confirmed by the user later, just emit the action.
If a request needs an app with no matching action, use open_app then tap/scroll steps only if obvious, else explain briefly in "say".
For general knowledge questions answer in "say" with no actions. Never output code, shell commands or URLs to run.
Contacts: use the nickname exactly as the user said it (e.g. "mummy", "papa", "her") — Sami resolves nicknames itself.
Known nicknames: ${ctx.aliases.entries.groupBy({ it.value }, { it.key }).entries.joinToString("; ") { (who, nicks) -> "$who = ${nicks.joinToString("/")}" }}
${ctx.lastContact?.let { "Person mentioned most recently: $it (\"him/her/usko/again\" likely means them)." } ?: ""}
${if (ctx.recentActions.isNotEmpty()) "Recent actions: " + ctx.recentActions.joinToString("; ") else ""}
${if (ctx.learned.isNotEmpty()) "Corrections the user taught you (phrase → what they meant):\n" + ctx.learned.entries.toList().takeLast(30).joinToString("\n") { "- \"${it.key}\" → \"${it.value}\"" } else ""}
Installed apps: ${ctx.apps.take(150).joinToString(", ")}
${if (ctx.facts.isNotEmpty()) "About the user (they told you):\n- " + ctx.facts.joinToString("\n- ") else ""}
""".trim()
}

/** Pure, unit-tested JSON → validated commands. Unknown or malformed actions are dropped. */
object PlanParser {
    fun parse(json: String, now: LocalDateTime): Planner.Plan {
        val o = runCatching { JSONObject(json.trim().removePrefix("```json").removeSuffix("```")) }.getOrNull()
            ?: return Planner.Plan(json.take(300), emptyList())
        val say = o.optString("say").take(400)
        val arr = o.optJSONArray("actions")
        val cmds = if (arr == null) emptyList() else (0 until minOf(arr.length(), 4)).mapNotNull { i -> arr.optJSONObject(i)?.let { toCommand(it, now) } }
        return Planner.Plan(say, cmds)
    }

    private fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }?.trim()?.take(200)
    private fun pct(o: JSONObject, k: String) = if (o.has(k)) o.optInt(k, -1).takeIf { it in 0..100 } else null

    fun toCommand(a: JSONObject, now: LocalDateTime): Command? = when (a.optString("action")) {
        "open_app" -> a.str("app")?.let { OpenApp(it.lowercase()) }
        "youtube_search" -> a.str("query")?.let { YouTubeSearch(it) }
        "youtube_latest" -> a.str("channel")?.let { YouTubeLatest(it) }
        "youtube_play" -> a.str("query")?.let { YouTubePlay(it) }
        "play_music" -> a.str("query")?.let { PlayMusic(it, when (a.optString("app")) { "spotify" -> MusicApp.SPOTIFY; "yt_music" -> MusicApp.YT_MUSIC; else -> MusicApp.DEFAULT }) }
        "media" -> when (a.optString("op")) { "play" -> Media(MediaOp.PLAY); "pause" -> Media(MediaOp.PAUSE); "next" -> Media(MediaOp.NEXT); "previous" -> Media(MediaOp.PREVIOUS); else -> null }
        "volume" -> when (a.optString("direction")) { "up" -> Volume(Direction.UP); "down" -> Volume(Direction.DOWN); "mute" -> Volume(Direction.MUTE); "max" -> Volume(Direction.MAX); else -> null }
        "volume_set" -> pct(a, "percent")?.let { VolumeSet(it) }
        "brightness" -> pct(a, "percent")?.let { Brightness(percent = it) }
        "flashlight" -> if (a.has("on")) Flashlight(a.optBoolean("on")) else null
        "dnd" -> if (a.has("on")) DoNotDisturb(a.optBoolean("on")) else null
        "settings" -> runCatching { OpenSettings(SettingsPage.valueOf(a.optString("page", "main").uppercase())) }.getOrNull()
        "home" -> Home
        "back" -> Global(GlobalOp.BACK)
        "scroll" -> Scroll(if (a.optString("dir") == "up") ScrollDir.UP else ScrollDir.DOWN)
        "tap" -> when {
            a.has("ordinal") -> a.optInt("ordinal", 0).takeIf { it in 1..20 }?.let { Tap(ordinal = it) }
            else -> a.str("target")?.let { Tap(target = it) }
        }
        "pay" -> { val amt = a.optDouble("amount", -1.0); a.str("name")?.takeIf { amt > 0 && amt <= 100000 }?.let { Pay(it, amt) } }
        "call" -> a.str("name")?.let { Call(it) }
        "whatsapp" -> a.str("name")?.let { WhatsApp(it, a.str("text")) }
        "alarm" -> a.str("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }?.let { Alarm(it.hour, it.minute) }
        "timer" -> a.optInt("seconds", 0).takeIf { it in 1..86_400 }?.let { Timer(it) }
        "reminder" -> {
            val at = a.str("time")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            val text = a.str("text")
            if (at != null && text != null && at.isAfter(now)) Reminder(text, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) else null
        }
        "photos" -> a.str("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { Photos(epochDay = it.toEpochDay()) }
        "screenshot_latest" -> Photos(screenshot = true)
        "instagram_profile" -> a.str("username")?.removePrefix("@")?.takeIf { Regex("""[A-Za-z0-9._]{1,30}""").matches(it) }?.let { InstagramProfile(it) }
        "shop_search" -> a.str("query")?.let { q -> runCatching { Store.valueOf(a.optString("store").uppercase()) }.getOrNull()?.let { ShopSearch(it, q) } }
        "remember" -> a.str("fact")?.let { Remember(it) }
        "missed_notifications" -> Missed
        "routine" -> when (a.optString("name")) { "good_night" -> Routine(RoutineName.GOOD_NIGHT); "good_morning" -> Routine(RoutineName.GOOD_MORNING); else -> null }
        "battery" -> Battery
        "screen_time" -> ScreenTime(a.str("app"))
        "web_search" -> a.str("query")?.let { WebAnswer(it) }
        "research" -> a.str("query")?.let { Research(it) }
        "class_next" -> ClassQuery(ClassKind.NEXT)
        "class_day" -> ClassQuery(ClassKind.DAY, dayOffset = a.optInt("offset", 0).coerceIn(0, 6))
        "where_room" -> a.str("venue")?.let { ClassQuery(ClassKind.WHERE, venue = it.uppercase()) }
        "open_file" -> a.str("query")?.let { OpenFile(it) }
        "photo_of" -> a.optJSONArray("people")?.let { arr -> (0 until arr.length()).map { arr.getString(it).lowercase() }.filter { it == "me" || it == "her" }.toSet() }?.takeIf { it.isNotEmpty() }?.let { PhotoOf(it) }
        "sami_setting" -> runCatching { SettingKey.valueOf(a.optString("key").uppercase()) }.getOrNull()?.let { k -> a.str("value")?.let { SamiSetting(k, it) } }
        "messages" -> Messages(a.str("app")?.lowercase(), a.str("person"))
        "mail" -> Mail(a.str("query"))
        else -> null
    }
}

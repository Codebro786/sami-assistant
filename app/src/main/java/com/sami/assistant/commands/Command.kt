package com.sami.assistant.commands

/**
 * Structured, validated command produced by the fast local parser (Path A) — and, from
 * Milestone 3, by the AI planner (Path B). Nothing here is executable code: every command
 * maps to a registered action handler in [com.sami.assistant.actions.ActionRegistry].
 */
sealed interface Command {
    data class OpenApp(val appName: String) : Command
    data class Volume(val direction: Direction, val stream: Stream = Stream.MEDIA, val steps: Int = 1) : Command
    data class VolumeSet(val percent: Int, val stream: Stream = Stream.MEDIA) : Command
    data class Media(val op: MediaOp) : Command
    data class Flashlight(val on: Boolean) : Command
    data class YouTubeSearch(val query: String) : Command
    data class OpenSettings(val page: SettingsPage) : Command
    data object Home : Command
    data class Brightness(val direction: Direction? = null, val percent: Int? = null) : Command
    data class DoNotDisturb(val on: Boolean) : Command

    // ---- Screen control: needs the user-enabled AccessibilityService ----
    sealed interface Screen : Command
    data class Global(val op: GlobalOp) : Screen
    data class Scroll(val dir: ScrollDir) : Screen
    /** Tap an element by visible text / description, or the Nth item of the main list. */
    data class Tap(val target: String? = null, val ordinal: Int? = null, val longPress: Boolean = false) : Screen
    data object ReadScreen : Screen

    enum class GlobalOp { BACK, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, SCREENSHOT, LOCK }
    enum class ScrollDir { UP, DOWN, LEFT, RIGHT }
    /** Ask a question about what's on screen (accessibility text → AI). Does not hide Sami. */
    data class ScreenAsk(val question: String) : Command

    // ---- Personal assistant ----
    data class Pay(val name: String, val amountRupees: Double) : Command
    data class Call(val name: String) : Command
    data class WhatsApp(val name: String, val text: String?) : Command
    data class Alarm(val hour: Int, val minute: Int, val label: String? = null) : Command
    data class Timer(val seconds: Int) : Command
    data class Reminder(val text: String, val atMillis: Long) : Command
    /** Photos taken on a calendar day (epoch-day), or the latest screenshot when [screenshot] is true. */
    data class Photos(val epochDay: Long? = null, val screenshot: Boolean = false) : Command
    data class PlayMusic(val query: String, val app: MusicApp = MusicApp.DEFAULT) : Command
    data class YouTubeLatest(val channel: String) : Command
    /** Play the top YouTube result (videos always open in YouTube ReVanced). */
    data class YouTubePlay(val query: String) : Command
    data class InstagramProfile(val username: String) : Command
    data class ShopSearch(val store: Store, val query: String) : Command
    data object Missed : Command
    data class Routine(val name: RoutineName) : Command
    data class Remember(val fact: String) : Command
    data object ForgetAll : Command
    data object Battery : Command
    data class ScreenTime(val appName: String? = null) : Command
    /** Multi-source web research ("check online if the RTX 5070 has thermal paste issues"). */
    data class Research(val query: String) : Command
    /** New chat messages from notifications, optionally for one app and/or one person. */
    data class Messages(val app: String? = null, val person: String? = null) : Command
    /** Gmail: important/unread mail, or a search ("mail from placement cell"). */
    data class Mail(val query: String? = null) : Command
    /** Live answer from the web (news, scores, prices, weather) via Groq's search-enabled model. */
    data class WebAnswer(val query: String) : Command
    /** Plain spoken answer from the AI (no device action). */
    data class Say(val text: String) : Command

    enum class MusicApp { DEFAULT, SPOTIFY, YT_MUSIC }
    enum class Store { FLIPKART, AMAZON, MYNTRA, BLINKIT }
    enum class RoutineName { GOOD_NIGHT, GOOD_MORNING }

    /** Find and open a document/photo anywhere on the phone by name or by the text inside it. */
    data class OpenFile(val query: String) : Command
    /** Random photo containing these people (labels taught in Settings, e.g. "me", "her"). */
    data class PhotoOf(val people: Set<String>) : Command
    /** Timetable questions: next class, a day's classes, where a room is. */
    data class ClassQuery(val kind: ClassKind, val dayOffset: Int = 0, val venue: String? = null) : Command
    enum class ClassKind { NEXT, DAY, WHERE }
    /** Change one of Sami's own settings by voice. */
    data class SamiSetting(val key: SettingKey, val value: String) : Command
    enum class SettingKey { WAKE, SLEEP_PAUSE, SENSITIVITY, CONTINUOUS, SPEECH_RATE, PITCH, REPLY_LANGUAGE, WAKE_TIME, SLEEP_TIME, AI_NOTIFICATIONS, AI_SCREEN }

    data object Stop : Command
    /** Not understood locally — goes to the AI planner (Groq). */
    data class Unknown(val text: String) : Command

    enum class Direction { UP, DOWN, MUTE, UNMUTE, MAX }
    enum class Stream { MEDIA, RING, ALARM }
    enum class MediaOp { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, FORWARD, REWIND }
    enum class SettingsPage { MAIN, WIFI, BLUETOOTH, DISPLAY, SOUND, BATTERY, APPS, ASSISTANT, ACCESSIBILITY }
}

package com.sami.assistant.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.sami.assistant.apps.AppMatcher
import com.sami.assistant.apps.AppRepository
import com.sami.assistant.commands.Command
import kotlin.math.roundToInt

private const val TAG = "SamiActions"

/** One entry in the registry: metadata + typed handler. */
class RegisteredAction<C : Command>(val action: Action<C>, val type: Class<C>)

/**
 * Central, typed registry. Commands (from the local parser or, later, the AI planner)
 * are dispatched only to handlers registered here — no dynamic code execution.
 */
class ActionRegistry(
    private val context: Context,
    private val apps: AppRepository,
    prefs: com.sami.assistant.core.SamiPrefs,
    contacts: com.sami.assistant.system.ContactsRepo,
    private val planner: com.sami.assistant.ai.Planner,
) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)

    private val entries = mutableListOf<RegisteredAction<*>>()
    lateinit var personal: PersonalActions
        private set
    val all: List<Action<*>> get() = entries.map { it.action }

    private inline fun <reified C : Command> register(a: Action<C>) { entries += RegisteredAction(a, C::class.java) }

    init {
        register(OpenAppAction()); register(VolumeAction()); register(VolumeSetAction())
        register(MediaAction()); register(FlashlightAction()); register(YouTubeAction())
        register(SettingsAction()); register(HomeAction()); register(ScreenAction())
        register(BrightnessAction()); register(DndAction())
        val p = PersonalActions(context, prefs, apps, contacts, planner, ::startActivity, ::execute)
        fn<Command.Pay>("pay.upi.prepare", "Prepare a UPI payment in Paytm (user authorises)", Risk.HIGH) { p.pay(it) }
        fn<Command.Call>("comm.call", "Call a contact (after confirmation)", Risk.HIGH) { p.call(it) }
        fn<Command.WhatsApp>("comm.whatsapp", "Open a WhatsApp chat / send a confirmed message", Risk.HIGH) { p.whatsapp(it) }
        fn<Command.Alarm>("clock.alarm", "Set an alarm", Risk.LOW) { p.alarm(it) }
        fn<Command.Timer>("clock.timer", "Start a timer", Risk.LOW) { p.timer(it) }
        fn<Command.Reminder>("clock.reminder", "Schedule a reminder notification", Risk.LOW) { p.reminder(it) }
        fn<Command.Photos>("media.photos", "Open photos from a date / latest screenshot", Risk.LOW) { p.photos(it) }
        fn<Command.YouTubeLatest>("media.youtube.latest", "Play a channel's latest upload", Risk.LOW) { p.youtubeLatest(it) }
        fn<Command.YouTubePlay>("media.youtube.play", "Play the top YouTube result in ReVanced", Risk.LOW) { p.youtubePlay(it) }
        fn<Command.PlayMusic>("media.music.play", "Play a song in YouTube Music / Spotify", Risk.LOW) { p.playMusic(it) }
        fn<Command.InstagramProfile>("social.instagram", "Open an Instagram profile", Risk.LOW) { p.instagram(it) }
        fn<Command.ShopSearch>("shop.search", "Search a shopping app", Risk.LOW) { p.shop(it) }
        fn<Command.Battery>("device.battery", "Report battery level", Risk.LOW) { p.battery() }
        fn<Command.ScreenTime>("device.screentime", "Report today's screen time", Risk.LOW) { p.screenTime(it) }
        fn<Command.Missed>("notif.digest", "Summarize recent notifications", Risk.MEDIUM) { p.missed() }
        fn<Command.ScreenAsk>("screen.ask", "Answer a question about the visible screen", Risk.MEDIUM) { p.screenAsk(it) }
        fn<Command.Routine>("routine.run", "Good night / good morning routines", Risk.LOW) { p.routine(it) }
        fn<Command.Remember>("memory.remember", "Remember a fact", Risk.LOW) { p.remember(it) }
        fn<Command.ForgetAll>("memory.forget", "Forget all remembered facts", Risk.LOW) { p.forgetAll() }
        fn<Command.Say>("ai.say", "Spoken answer", Risk.LOW) { ActionResult.Success(it.text) }
        fn<Command.WebAnswer>("ai.web", "Live web answer", Risk.LOW) {
            runCatching { ActionResult.Success(planner.webAnswer(it.query, com.sami.assistant.core.Lang.hinglish)) }
                .getOrElse { e -> ActionResult.Failure(e.message ?: "I couldn't look that up.") }
        }
        fn<Command.Research>("ai.research", "Multi-source web research", Risk.LOW) { p.research(it) }
        fn<Command.Messages>("comm.messages", "Summarize new chat messages from notifications", Risk.MEDIUM) { p.messages(it) }
        fn<Command.Mail>("comm.gmail", "Find important Gmail messages (read-only)", Risk.MEDIUM) { p.mail(it) }
        fn<Command.ClassQuery>("timetable.query", "Next class, today's classes, where a room is", Risk.LOW) { p.classQuery(it) }
        fn<Command.OpenFile>("files.open", "Find and open a document or photo on the phone", Risk.LOW) { p.openFile(it) }
        fn<Command.PhotoOf>("photos.people", "Open a random photo of chosen people (on-device face recognition)", Risk.LOW) { p.photoOf(it) }
        fn<Command.SamiSetting>("sami.settings", "Change Sami's own settings by voice", Risk.LOW) { p.samiSetting(it) }
        personal = p
    }

    private inline fun <reified C : Command> fn(id: String, desc: String, risk: Risk, crossinline body: suspend (C) -> ActionResult) =
        register(object : Action<C> {
            override val id = id; override val description = desc; override val risk = risk
            override suspend fun execute(command: C) = body(command)
        })

    suspend fun execute(command: Command): ActionResult {
        val entry = entries.firstOrNull { it.type.isInstance(command) }
            ?: return ActionResult.Unsupported("I can't do that yet.")
        @Suppress("UNCHECKED_CAST")
        val action = entry.action as Action<Command>
        action.validate(command)?.let { return ActionResult.Failure(it) }
        return try {
            action.execute(command).also { Log.i(TAG, "${action.id} -> $it") }
        } catch (e: SecurityException) {
            Log.w(TAG, "${action.id} security", e); ActionResult.Failure("Android blocked that action.", e)
        } catch (e: Exception) {
            Log.e(TAG, "${action.id} failed", e); ActionResult.Failure("Sorry, that didn't work.", e)
        }
    }

    fun startActivity(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: ActivityNotFoundException) { false }

    // ---------------------------------------------------------------------------------------

    inner class OpenAppAction : Action<Command.OpenApp> {
        override val id = "app.open"
        override val description = "Open an installed app by name"
        override val risk = Risk.LOW
        override fun validate(command: Command.OpenApp) = if (command.appName.isBlank()) "Which app?" else null
        override suspend fun execute(command: Command.OpenApp): ActionResult {
            val match = AppMatcher.match(command.appName, apps.launchableApps())
                ?: return ActionResult.Failure("I couldn't find an app called ${command.appName}.")
            val intent = apps.launchIntent(match.packageName)
                ?: return ActionResult.Failure("${match.label} can't be opened.")
            return if (startActivity(intent)) ActionResult.Success(com.sami.assistant.core.Lang.t("Opening ${match.label}.", "${match.label} khol rahi hoon."))
            else ActionResult.Failure("${match.label} didn't open.")
        }
    }

    private fun streamOf(s: Command.Stream) = when (s) {
        Command.Stream.MEDIA -> AudioManager.STREAM_MUSIC
        Command.Stream.RING -> AudioManager.STREAM_RING
        Command.Stream.ALARM -> AudioManager.STREAM_ALARM
    }
    private fun percent(stream: Int) = (audio.getStreamVolume(stream) * 100f / audio.getStreamMaxVolume(stream)).roundToInt()
    private fun name(s: Command.Stream) = when (s) { Command.Stream.MEDIA -> "Volume"; Command.Stream.RING -> "Ring volume"; Command.Stream.ALARM -> "Alarm volume" }

    inner class VolumeAction : Action<Command.Volume> {
        override val id = "device.volume.adjust"
        override val description = "Raise, lower, mute or max a volume stream"
        override val risk = Risk.LOW
        override suspend fun execute(command: Command.Volume): ActionResult {
            val stream = streamOf(command.stream)
            val before = audio.getStreamVolume(stream)
            when (command.direction) {
                Command.Direction.UP -> repeat(command.steps) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                Command.Direction.DOWN -> repeat(command.steps) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                Command.Direction.MUTE -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                Command.Direction.UNMUTE -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                Command.Direction.MAX -> audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream), AudioManager.FLAG_SHOW_UI)
            }
            val after = audio.getStreamVolume(stream)
            val muted = audio.isStreamMute(stream)
            return when {
                command.direction == Command.Direction.MUTE && muted -> ActionResult.Success("Muted.")
                command.direction == Command.Direction.MUTE -> ActionResult.Failure("Android didn't allow muting that.")
                after != before || command.direction == Command.Direction.UNMUTE -> ActionResult.Success(com.sami.assistant.core.Lang.t("${name(command.stream)} ${percent(stream)} percent.", "Volume ${percent(stream)} percent kar diya."))
                command.direction == Command.Direction.UP || command.direction == Command.Direction.MAX ->
                    if (after == audio.getStreamMaxVolume(stream)) ActionResult.Success("Already at maximum.")
                    else ActionResult.Failure("Volume didn't change. Do Not Disturb may be blocking it.")
                else -> if (after == 0) ActionResult.Success("Already at minimum.")
                    else ActionResult.Failure("Volume didn't change. Do Not Disturb may be blocking it.")
            }
        }
    }

    inner class VolumeSetAction : Action<Command.VolumeSet> {
        override val id = "device.volume.set"
        override val description = "Set a volume stream to a percentage"
        override val risk = Risk.LOW
        override fun validate(command: Command.VolumeSet) = if (command.percent !in 0..100) "Volume must be 0 to 100." else null
        override suspend fun execute(command: Command.VolumeSet): ActionResult {
            val stream = streamOf(command.stream)
            val target = (command.percent / 100f * audio.getStreamMaxVolume(stream)).roundToInt()
            audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
            return if (audio.getStreamVolume(stream) == target) ActionResult.Success("${name(command.stream)} set to ${percent(stream)} percent.")
            else ActionResult.Failure("Android didn't allow that volume change.")
        }
    }

    inner class MediaAction : Action<Command.Media> {
        override val id = "media.control"
        override val description = "Send media key to the active media session"
        override val risk = Risk.LOW
        override suspend fun execute(command: Command.Media): ActionResult {
            val key = when (command.op) {
                Command.MediaOp.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                Command.MediaOp.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                Command.MediaOp.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                Command.MediaOp.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                Command.MediaOp.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                Command.MediaOp.FORWARD -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                Command.MediaOp.REWIND -> KeyEvent.KEYCODE_MEDIA_REWIND
            }
            val wasPlaying = audio.isMusicActive
            if (command.op == Command.MediaOp.PAUSE && !wasPlaying) return ActionResult.Success("Nothing is playing.")
            val t = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0))
            // Media key delivery is fire-and-forget; we can only observe whether audio is active.
            return when (command.op) {
                Command.MediaOp.PLAY -> ActionResult.Success("Sent play to the last media app.")
                Command.MediaOp.PAUSE -> ActionResult.Success("Paused.")
                Command.MediaOp.NEXT -> ActionResult.Success("Next.")
                Command.MediaOp.PREVIOUS -> ActionResult.Success("Previous.")
                else -> ActionResult.Success("Done.")
            }
        }
    }

    inner class FlashlightAction : Action<Command.Flashlight> {
        override val id = "device.flashlight"
        override val description = "Turn the torch on or off"
        override val risk = Risk.LOW
        override suspend fun execute(command: Command.Flashlight): ActionResult {
            val id = camera.cameraIdList.firstOrNull {
                val c = camera.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return ActionResult.Unsupported("This phone has no flashlight I can use.")
            return try {
                camera.setTorchMode(id, command.on)
                ActionResult.Success(if (command.on) com.sami.assistant.core.Lang.t("Flashlight on.", "Torch on kar di.") else com.sami.assistant.core.Lang.t("Flashlight off.", "Torch band kar di."))
            } catch (e: Exception) {
                ActionResult.Failure("The camera is busy, so I can't use the flashlight.", e)
            }
        }
    }

    inner class YouTubeAction : Action<Command.YouTubeSearch> {
        override val id = "media.youtube.search"
        override val description = "Search YouTube"
        override val risk = Risk.LOW
        override suspend fun execute(command: Command.YouTubeSearch): ActionResult {
            val pkg = listOf("app.revanced.android.youtube", "com.google.android.youtube").firstOrNull {
                runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess } ?: "com.google.android.youtube"
            val app = Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra("query", command.query)
            if (startActivity(app)) return ActionResult.Success("Searching YouTube for ${command.query}.")
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(command.query)))
            return if (startActivity(web)) ActionResult.Success("Searching YouTube for ${command.query}.")
            else ActionResult.Failure("YouTube isn't available.")
        }
    }

    inner class SettingsAction : Action<Command.OpenSettings> {
        override val id = "system.settings.open"
        override val description = "Open a system settings screen (or settings panel for Wi-Fi / Bluetooth)"
        override val risk = Risk.LOW
        override suspend fun execute(command: Command.OpenSettings): ActionResult {
            val (action, label) = when (command.page) {
                Command.SettingsPage.MAIN -> Settings.ACTION_SETTINGS to "settings"
                // Android 10+ forbids apps toggling Wi-Fi directly; the panel lets the user do it in one tap.
                Command.SettingsPage.WIFI -> Settings.Panel.ACTION_WIFI to "Wi-Fi controls"
                Command.SettingsPage.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth settings"
                Command.SettingsPage.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS to "display settings"
                Command.SettingsPage.SOUND -> Settings.ACTION_SOUND_SETTINGS to "sound settings"
                Command.SettingsPage.BATTERY -> Settings.ACTION_BATTERY_SAVER_SETTINGS to "battery settings"
                Command.SettingsPage.APPS -> Settings.ACTION_APPLICATION_SETTINGS to "app settings"
                Command.SettingsPage.ASSISTANT -> Settings.ACTION_VOICE_INPUT_SETTINGS to "assistant settings"
                Command.SettingsPage.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS to "accessibility settings"
            }
            return if (startActivity(Intent(action))) ActionResult.HandedOff("Opening $label.")
            else if (startActivity(Intent(Settings.ACTION_SETTINGS))) ActionResult.HandedOff("Opening settings.")
            else ActionResult.Failure("I couldn't open settings.")
        }
    }

    inner class HomeAction : Action<Command.Home> {
        override val id = "nav.home"
        override val description = "Go to the home screen"
        override val risk = Risk.LOW
        override suspend fun execute(command: Command.Home): ActionResult =
            if (startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))) ActionResult.Success(com.sami.assistant.core.Lang.t("Going home.", "Home pe aa gaye."))
            else ActionResult.Failure("I couldn't reach the home screen.")
    }

    inner class BrightnessAction : Action<Command.Brightness> {
        override val id = "device.brightness"
        override val description = "Change screen brightness (needs 'Modify system settings')"
        override val requiredPermissions = listOf(android.Manifest.permission.WRITE_SETTINGS)
        override val risk = Risk.LOW
        override suspend fun execute(command: Command.Brightness): ActionResult {
            if (!Settings.System.canWrite(context)) {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                return ActionResult.HandedOff("Allow Sami to modify system settings, then ask me again.")
            }
            val cr = context.contentResolver
            // Brightness is perceptually non-linear; map percent through a square curve onto 1..255.
            fun toRaw(p: Int) = (1 + 254 * (p / 100f) * (p / 100f)).roundToInt().coerceIn(1, 255)
            fun toPct(raw: Int) = (kotlin.math.sqrt((raw - 1) / 254f) * 100).roundToInt()
            val cur = toPct(Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
            val target = command.percent ?: when (command.direction) {
                Command.Direction.UP -> cur + 20; Command.Direction.DOWN -> cur - 20
                Command.Direction.MAX -> 100; Command.Direction.MUTE -> 0; else -> cur
            }.coerceIn(0, 100)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val ok = Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, toRaw(target))
            return if (ok) ActionResult.Success(com.sami.assistant.core.Lang.t("Brightness $target percent.", "Brightness $target percent kar di.")) else ActionResult.Failure("Android didn't accept the brightness change.")
        }
    }

    inner class DndAction : Action<Command.DoNotDisturb> {
        override val id = "device.dnd"
        override val description = "Turn Do Not Disturb on/off (needs DND access)"
        override val requiredPermissions = listOf(android.Manifest.permission.ACCESS_NOTIFICATION_POLICY)
        override val risk = Risk.LOW
        override suspend fun execute(command: Command.DoNotDisturb): ActionResult {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                return ActionResult.HandedOff("Give Sami Do Not Disturb access, then ask me again.")
            }
            val filter = if (command.on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            nm.setInterruptionFilter(filter)
            return if (nm.currentInterruptionFilter == filter) ActionResult.Success(if (command.on) "Do Not Disturb is on." else "Do Not Disturb is off.")
            else ActionResult.Failure("Android didn't change Do Not Disturb.")
        }
    }
}

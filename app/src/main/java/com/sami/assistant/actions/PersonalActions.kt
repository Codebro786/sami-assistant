package com.sami.assistant.actions

import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.sami.assistant.ai.Planner
import com.sami.assistant.apps.AppMatcher
import com.sami.assistant.apps.AppRepository
import com.sami.assistant.commands.Command
import com.sami.assistant.core.SamiPrefs
import com.sami.assistant.core.Lang
import com.sami.assistant.system.Contact
import com.sami.assistant.system.ContactsRepo
import com.sami.assistant.system.NotificationStore
import com.sami.assistant.system.Reminders
import com.sami.assistant.system.SamiAccessibilityService
import com.sami.assistant.system.SamiNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Personal-assistant actions. Each reports what actually happened; sensitive ones return [ActionResult.NeedsConfirmation]. */
class PersonalActions(
    private val context: Context,
    private val prefs: SamiPrefs,
    private val apps: AppRepository,
    private val contacts: ContactsRepo,
    private val planner: Planner,
    private val launch: (Intent) -> Boolean,
    private val run: suspend (Command) -> ActionResult,
) {
    private fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    private fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    private fun rupees(a: Double) = if (a % 1.0 == 0.0) "₹${a.toLong()}" else "₹${"%.2f".format(a)}"

    private suspend fun resolve(name: String): Pair<Contact?, ActionResult?> = when (val m = contacts.find(name)) {
        is ContactsRepo.Match.One -> m.contact to null
        is ContactsRepo.Match.Many -> null to ActionResult.Failure("I found ${m.options.size} people: ${m.options.joinToString(", ") { it.name }}. Say the full name.")
        ContactsRepo.Match.None -> null to ActionResult.Failure("I couldn't find $name in your contacts.")
        ContactsRepo.Match.NoPermission -> null to ActionResult.Unsupported("I need Contacts permission. Turn it on in Sami › Settings.")
    }

    // ---- UPI payment preparation (never automates authorisation) ----------------------------
    val vpaRegex = Regex("""^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z][a-zA-Z0-9]{2,64}$""")
    private val paytm = "net.one97.paytm"

    suspend fun pay(c: Command.Pay): ActionResult {
        if (c.amountRupees <= 0 || c.amountRupees > 100_000) return ActionResult.Failure("That amount isn't valid.")
        val (contact, err) = resolve(c.name); err?.let { return it }
        contact!!
        val saved = prefs.payees[contact.key] ?: prefs.payees["name:${contact.name.lowercase()}"]
        // Most Indian UPI IDs are "<10-digit mobile>@<app handle>"; build that from the contact's number.
        val mobile = contact.phones.map { ContactsRepo.normalize(it) }.firstOrNull { it.length == 12 && it.startsWith("91") }?.drop(2)
        val card = ConfirmCard(
            title = "Pay ${rupees(c.amountRupees)}",
            lines = listOfNotNull("To  ${contact.name}", saved?.let { "UPI  $it" }, "Via  Paytm",
                if (saved == null) "Check the name Paytm shows before entering your PIN" else "You'll enter your UPI PIN in Paytm"),
            spoken = if (saved != null) "Pay ${contact.name} ${c.amountRupees.toLong()} rupees on Paytm? Tap open in Paytm."
                     else if (mobile != null) "Pay ${contact.name.split(" ").first()} ${c.amountRupees.toLong()} rupees? I've used their mobile number. Pick their UPI app if it's not Paytm, then confirm."
                     else "I need ${contact.name.split(" ").first()}'s UPI ID once. Type it in and tap confirm.",
            // No Sami fingerprint: Paytm's UPI PIN is the real authorisation.
            confirmLabel = "Open in Paytm", requireBiometric = false, voiceConfirm = false, editableAmount = c.amountRupees,
            inputHint = if (saved == null) "UPI ID (e.g. name@paytm, 98xxxxxx@ybl)" else null,
            inputValidator = if (saved == null) { v -> if (vpaRegex.matches(v.trim())) null else "That doesn't look like a UPI ID" } else null,
            inputPrefill = if (saved == null) mobile?.let { "$it@ptyes" } else null,
            inputChips = if (saved == null && mobile != null) listOf("ptyes", "paytm", "ybl", "ibl", "axl", "okaxis") else emptyList(),
        )
        lastContact = contact.name
        return ActionResult.NeedsConfirmation(card) { input ->
            // Input is "<upi id>\n<amount>" from the card (UPI part empty when already saved).
            val parts = input.orEmpty().split("\n")
            val amount = parts.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0 && it <= 100_000 } ?: c.amountRupees
            val vpa = saved ?: parts[0].trim().also { prefs.payees = prefs.payees + (contact.key to it) }
            val uri = Uri.Builder().scheme("upi").authority("pay")
                .appendQueryParameter("pa", vpa).appendQueryParameter("pn", contact.name)
                .appendQueryParameter("am", "%.2f".format(Locale.US, amount)).appendQueryParameter("cu", "INR")
                .appendQueryParameter("tn", "Sent via Sami").build()
            val intent = Intent(Intent.ACTION_VIEW, uri).apply { if (installed(paytm)) setPackage(paytm) }
            // Paytm takes over from here. Sami can't see the outcome, so it never claims the payment succeeded.
            if (!launch(intent)) return@NeedsConfirmation ActionResult.Failure("No UPI app could open this payment.")
            // Screen control switches itself off inside UPI apps (they block accessibility), so Sami can't read Paytm's screen;
            // Paytm shows the bank-verified name itself.
            ActionResult.HandedOff(Lang.t("Opening Paytm. Check it shows ${contact.name.split(" ").first()}'s verified name, then enter your PIN.",
                "Paytm khul raha hai. Verified naam check karke PIN daalna."))
        }
    }

    /**
     * Reads Paytm's payment screen (screen control) and checks the account-holder name it shows
     * against who the user meant. Sami only reads; it never taps Pay or enters the PIN.
     */
    private suspend fun verifyPayee(contactName: String, vpa: String): ActionResult {
        val svc = SamiAccessibilityService.instance
            ?: return ActionResult.HandedOff(Lang.t("Paytm is open. Check the name it shows before entering your PIN.", "Paytm khul gaya. PIN daalne se pehle naam check kar lena."))
        // Tokens that should appear in the verified name: parts of the contact name, nicknames' target, and the UPI ID's letters.
        val vpaWords = Regex("""[a-z]{4,}""").findAll(vpa.substringBefore('@').lowercase()).map { it.value }.toList()
        val nameWords = contactName.lowercase().split(Regex("""[^a-z]+""")).filter { it.length >= 3 }
        val expected = (vpaWords + nameWords).distinct()
        repeat(16) {
            delay(500)
            val root = svc.targetRoot() ?: return@repeat
            if (root.packageName?.toString() != paytm) return@repeat
            val texts = svc.allNodes(root).filter { it.isVisibleToUser }.map { svc.labelOf(it) }.filter { it.length in 3..60 }
            // Paytm shows the bank-registered name near the UPI ID on the confirm screen.
            val hit = texts.firstOrNull { t -> val l = t.lowercase(); expected.any { w -> l.contains(w) } && !l.contains("@") }
            if (hit != null) return ActionResult.HandedOff(Lang.t(
                "Paytm shows “$hit”, which matches. Enter your PIN only if the amount is right.",
                "Paytm pe “$hit” dikh raha hai, naam match karta hai. Amount sahi ho tabhi PIN daalna."))
            val candidates = texts.filter { t -> t.count { it.isUpperCase() } >= 4 && t.none { it.isDigit() } && !t.contains("@") }
            if (texts.any { it.contains(vpa, ignoreCase = true) } && candidates.isNotEmpty() && it >= 6)
                return ActionResult.HandedOff(Lang.t(
                    "Careful: Paytm shows “${candidates.first()}”, which doesn't look like $contactName. Don't pay unless you're sure.",
                    "Dhyan do: Paytm pe “${candidates.first()}” dikh raha hai, ye $contactName jaisa nahi lag raha. Pakka ho tabhi pay karna."))
        }
        return ActionResult.HandedOff(Lang.t(
            "I couldn't read the name on Paytm. Check it says $contactName before entering your PIN.",
            "Paytm pe naam padh nahi payi. PIN se pehle check karo ki naam $contactName ka hai."))
    }

    // ---- Calls & WhatsApp -----------------------------------------------------------------
    /** Last person Sami called/messaged/paid, so "call her again" / "usko message karo" works. */
    var lastContact: String? = null

    suspend fun call(c: Command.Call): ActionResult {
        val name = if (c.name in setOf("him", "usko", "use", "unko", "again", "back", "them") && lastContact != null) lastContact!! else c.name
        val (contact, err) = resolve(name); err?.let { return it }
        contact!!
        lastContact = contact.name
        val number = contact.phones.first()
        // User chose direct calling (no confirmation card). Placed through the system Phone app.
        if (!has(Manifest.permission.CALL_PHONE)) {
            return if (launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))) ActionResult.HandedOff("Tap call to ring ${contact.name}.")
            else ActionResult.Failure("I couldn't open the dialer.")
        }
        val telecom = context.getSystemService(android.telecom.TelecomManager::class.java)
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).apply { telecom.defaultDialerPackage?.let { setPackage(it) } }
        return if (launch(intent) || launch(intent.setPackage(null))) ActionResult.Success(Lang.t("Calling ${contact.name}.", "${contact.name} ko call kar rahi hoon."))
        else ActionResult.Failure("I couldn't start the call.")
    }

    suspend fun whatsapp(c: Command.WhatsApp): ActionResult {
        if (!installed("com.whatsapp")) return ActionResult.Failure("WhatsApp isn't installed.")
        val (contact, err) = resolve(if (c.name in setOf("him", "usko", "use", "unko", "them") && lastContact != null) lastContact!! else c.name); err?.let { return it }
        contact!!
        lastContact = contact.name
        val num = ContactsRepo.normalize(contact.phones.first())
        val url = "https://wa.me/$num" + (c.text?.let { "?text=" + Uri.encode(it) } ?: "")
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp")
        if (c.text == null) return if (launch(open)) ActionResult.Success("Opening your chat with ${contact.name}.") else ActionResult.Failure("WhatsApp didn't open.")
        return ActionResult.NeedsConfirmation(ConfirmCard("Send to ${contact.name}?", listOf("“${c.text}”", "on WhatsApp"),
            "Tap send to message ${contact.name}.", "Send", voiceConfirm = false)) {
            if (!launch(open)) return@NeedsConfirmation ActionResult.Failure("WhatsApp didn't open.")
            val svc = SamiAccessibilityService.instance
                ?: return@NeedsConfirmation ActionResult.HandedOff("Message is typed in WhatsApp. Tap send.")
            // Wait for the chat to load, then press WhatsApp's Send button (verified by its accessibility label).
            repeat(12) {
                delay(400)
                val root = svc.targetRoot()
                val send = root?.let { r -> svc.allNodes(r).firstOrNull { n -> n.isVisibleToUser && n.contentDescription?.toString().equals("Send", true) } }
                if (send != null) {
                    return@NeedsConfirmation if ((svc.clickableTarget(send, false) ?: send).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        ActionResult.Success("Sent to ${contact.name}.") else ActionResult.HandedOff("Message is ready. Tap send.")
                }
            }
            ActionResult.HandedOff("Message is typed in WhatsApp. Tap send.")
        }
    }

    // ---- Clock ------------------------------------------------------------------------------
    fun alarm(c: Command.Alarm): ActionResult {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, c.hour).putExtra(AlarmClock.EXTRA_MINUTES, c.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).apply { c.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
        val t = java.time.LocalTime.of(c.hour, c.minute).format(DateTimeFormatter.ofPattern("h:mm a"))
        return if (launch(i)) ActionResult.Success("Alarm set for $t.") else ActionResult.Failure("The Clock app didn't accept the alarm.")
    }

    fun timer(c: Command.Timer): ActionResult {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, c.seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val m = c.seconds / 60; val s = c.seconds % 60
        val spoken = listOfNotNull(m.takeIf { it > 0 }?.let { "$it minute${if (it > 1) "s" else ""}" }, s.takeIf { it > 0 }?.let { "$it seconds" }).joinToString(" ")
        return if (launch(i)) ActionResult.Success("Timer for $spoken started.") else ActionResult.Failure("The Clock app didn't start the timer.")
    }

    fun reminder(c: Command.Reminder): ActionResult {
        val at = Instant.ofEpochMilli(c.atMillis).atZone(ZoneId.systemDefault())
        val day = if (at.toLocalDate() == LocalDate.now()) "today" else if (at.toLocalDate() == LocalDate.now().plusDays(1)) "tomorrow" else at.format(DateTimeFormatter.ofPattern("d MMM"))
        return if (Reminders.schedule(context, c.text, c.atMillis)) ActionResult.Success("I'll remind you to ${c.text} at ${at.format(DateTimeFormatter.ofPattern("h:mm a"))} $day.")
        else ActionResult.Failure("Android didn't let me schedule the reminder.")
    }

    // ---- Photos -----------------------------------------------------------------------------
    suspend fun photos(c: Command.Photos): ActionResult = withContext(Dispatchers.IO) {
        val perm = Manifest.permission.READ_MEDIA_IMAGES
        if (!has(perm)) return@withContext ActionResult.Unsupported("I need Photos permission. Turn it on in Sami › Settings.")
        val uriBase = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val (sel, args, label) = if (c.screenshot) Triple("${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("%Screenshots%"), "your latest screenshot")
        else {
            val d = LocalDate.ofEpochDay(c.epochDay!!)
            val start = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); val end = start + 86_400_000
            Triple("${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?", arrayOf("$start", "$end"),
                d.format(DateTimeFormatter.ofPattern("d MMMM yyyy")))
        }
        val ids = mutableListOf<Long>()
        context.contentResolver.query(uriBase, arrayOf(MediaStore.Images.Media._ID), sel, args, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { cur ->
            while (cur.moveToNext()) ids += cur.getLong(0)
        }
        if (ids.isEmpty()) return@withContext ActionResult.Failure(if (c.screenshot) "I couldn't find any screenshots." else "No photos from $label.")
        val first = ContentUris.withAppendedId(uriBase, if (c.screenshot) ids.first() else ids.last()) // earliest of the day
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(first, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { if (installed("com.oneplus.gallery")) setPackage("com.oneplus.gallery") }
        val ok = withContext(Dispatchers.Main) { launch(view) || launch(view.setPackage(null)) }
        if (!ok) ActionResult.Failure("The gallery didn't open.")
        else if (c.screenshot) ActionResult.Success("Here's your latest screenshot.")
        else ActionResult.Success("Found ${ids.size} photo${if (ids.size > 1) "s" else ""} from $label. Swipe to see the rest.")
    }

    // ---- YouTube / music -------------------------------------------------------------------
    val youtubePkg get() = listOf("app.revanced.android.youtube", "com.google.android.youtube").firstOrNull { installed(it) }

    suspend fun youtubeLatest(c: Command.YouTubeLatest): ActionResult {
        val id = runCatching { YouTubeFeed.latestVideo(c.channel) }.getOrNull()
            ?: return ActionResult.Failure("I couldn't find ${c.channel}'s latest video. Check the name or your internet.")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${id.first}")).apply { youtubePkg?.let { setPackage(it) } }
        return if (launch(i)) ActionResult.Success("Playing ${id.second}.") else ActionResult.Failure("YouTube didn't open.")
    }

    suspend fun youtubePlay(c: Command.YouTubePlay): ActionResult {
        val top = runCatching { YouTubeFeed.topVideo(c.query) }.getOrNull()
        val intent = if (top != null) Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${top.first}"))
                     else Intent(Intent.ACTION_SEARCH).putExtra("query", c.query)
        youtubePkg?.let { intent.setPackage(it) }
        return if (launch(intent)) ActionResult.Success(top?.let { Lang.t("Playing ${it.second}.", "${it.second} chala rahi hoon.") } ?: "Searching YouTube for ${c.query}.")
        else ActionResult.Failure("YouTube didn't open.")
    }

    suspend fun playMusic(c: Command.PlayMusic): ActionResult {
        val ytm = listOf("app.morphe.android.apps.youtube.music", "app.revanced.android.apps.youtube.music", "com.google.android.apps.youtube.music").firstOrNull { installed(it) }
        val spotify = "com.spotify.music".takeIf { installed(it) }
        val target = when (c.app) { Command.MusicApp.SPOTIFY -> spotify; Command.MusicApp.YT_MUSIC -> ytm; Command.MusicApp.DEFAULT -> ytm ?: spotify }
            ?: return ActionResult.Failure("I don't see a music app for that.")
        val audio = context.getSystemService(android.media.AudioManager::class.java)

        if (target == ytm) {
            // Resolve the actual track first (YouTube search is forgiving: "a weekend" finds The Weeknd),
            // then open that exact track — YT Music starts playing a watch link immediately.
            val track = runCatching { YouTubeFeed.topVideo("${c.query} song") }.getOrNull()
                ?.let { (id, t) -> id to t.split(" | ").take(2).joinToString(" – ").replace(Regex("""\s*[(\[](official|lyric|full|audio|video|hd|4k)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "").trim() }
            if (track != null) {
                val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=${track.first}")).setPackage(target)
                if (!launch(open)) return ActionResult.Failure("YouTube Music didn't open.")
                // Report honestly: only say "playing" if audio actually started.
                repeat(10) { delay(500); if (audio.isMusicActive) return ActionResult.Success(Lang.t("Playing ${track.second}.", "${track.second} chala rahi hoon.")) }
                return ActionResult.HandedOff(Lang.t("Opened ${track.second} in YouTube Music. Tap play if it hasn't started.",
                    "YouTube Music mein ${track.second} khol diya. Shuru na ho toh play dabao."))
            }
            val search = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(c.query))).setPackage(target)
            return if (launch(search)) ActionResult.HandedOff("I couldn't find the exact song, so I searched YouTube Music for ${c.query}.")
            else ActionResult.Failure("YouTube Music didn't open.")
        }

        // Spotify: play-from-search, then verify playback.
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(target)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").putExtra(android.app.SearchManager.QUERY, c.query)
        if (launch(search)) {
            repeat(10) { delay(500); if (audio.isMusicActive) return ActionResult.Success("Playing ${c.query} on Spotify.") }
        }
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(c.query))).setPackage(target)
        return if (launch(fallback)) ActionResult.HandedOff("Searching Spotify for ${c.query}. Tap the song to play.") else ActionResult.Failure("Spotify didn't open.")
    }

    fun instagram(c: Command.InstagramProfile): ActionResult {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/_u/${Uri.encode(c.username)}")).apply { if (installed("com.instagram.android")) setPackage("com.instagram.android") }
        return if (launch(i)) ActionResult.Success("Opening @${c.username} on Instagram.") else ActionResult.Failure("Instagram didn't open.")
    }

    fun shop(c: Command.ShopSearch): ActionResult {
        val q = Uri.encode(c.query)
        val (url, pkg) = when (c.store) {
            Command.Store.FLIPKART -> "https://www.flipkart.com/search?q=$q" to "com.flipkart.android"
            Command.Store.AMAZON -> "https://www.amazon.in/s?k=$q" to "in.amazon.mShop.android.shopping"
            Command.Store.MYNTRA -> "https://www.myntra.com/$q" to "com.myntra.android"
            Command.Store.BLINKIT -> "https://blinkit.com/s/?q=$q" to "com.grofers.customerapp"
        }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { if (installed(pkg)) setPackage(pkg) }
        val store = c.store.name.lowercase().replaceFirstChar { it.uppercase() }
        return if (launch(i) || launch(i.setPackage(null))) ActionResult.Success("Searching $store for ${c.query}.") else ActionResult.Failure("$store didn't open.")
    }

    // ---- Awareness --------------------------------------------------------------------------
    fun battery(): ActionResult {
        val bm = context.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val left = if (!charging) bm.computeChargeTimeRemaining().let { "" } else bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { " Full in about ${it / 60000} minutes." } ?: ""
        return ActionResult.Success("Battery is at $pct percent${if (charging) " and charging." else "."}$left")
    }

    suspend fun screenTime(c: Command.ScreenTime): ActionResult = withContext(Dispatchers.IO) {
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
            withContext(Dispatchers.Main) { launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            return@withContext ActionResult.HandedOff("Give Sami usage access, then ask again.")
        }
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())
            .filterValues { it.totalTimeVisible > 60_000 }.filterKeys { it != context.packageName && !it.contains("launcher") }
        fun fmt(ms: Long) = (ms / 60000).let { m ->
            val h = m / 60; val r = m % 60
            if (h > 0) "$h hour${if (h > 1) "s" else ""} $r minute${if (r != 1L) "s" else ""}" else "$m minute${if (m != 1L) "s" else ""}"
        }
        if (c.appName != null) {
            val entry = AppMatcher.match(c.appName, apps.launchableApps())
                ?: return@withContext ActionResult.Failure("I don't know an app called ${c.appName}.")
            val ms = stats[entry.packageName]?.totalTimeVisible ?: 0
            return@withContext ActionResult.Success("You've used ${entry.label} for ${fmt(ms)} today.")
        }
        val total = stats.values.sumOf { it.totalTimeVisible }
        val top = stats.entries.sortedByDescending { it.value.totalTimeVisible }.take(3).map { (pkg, u) ->
            val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            "$label ${fmt(u.totalTimeVisible)}"
        }
        ActionResult.Success("Screen time today is ${fmt(total)}. Top: ${top.joinToString(", ")}.")
    }

    suspend fun missed(): ActionResult {
        if (!SamiNotificationListener.connected)
            return ActionResult.Unsupported("I need notification access. Turn it on in Sami › Settings.")
        val items = NotificationStore.recent(System.currentTimeMillis() - 6 * 3_600_000)
        if (items.isEmpty()) return ActionResult.Success("Nothing new in the last few hours.")
        val byApp = items.groupBy { it.app }
        val local = byApp.entries.sortedByDescending { it.value.size }.take(4).joinToString(". ") { (app, list) ->
            val people = list.map { it.title }.filter { it.isNotBlank() }.distinct().take(3)
            "${list.size} from $app" + if (people.isNotEmpty()) " (${people.joinToString(", ")})" else ""
        }
        if (!prefs.aiNotifications || prefs.groqKey.isNullOrBlank()) return ActionResult.Success("You have $local.")
        val text = items.takeLast(40).joinToString("\n") { "[${it.app}] ${it.title}: ${it.text}" }
        return runCatching { ActionResult.Success(planner.answer("Summarize what the user missed from these phone notifications. Mention who messaged and anything urgent.", text)) }
            .getOrElse { ActionResult.Success("You have $local.") }
    }

    suspend fun screenAsk(c: Command.ScreenAsk): ActionResult {
        val svc = SamiAccessibilityService.instance ?: return ActionResult.Unsupported("Turn on screen control so I can see the screen.")
        if (!prefs.aiScreen) return ActionResult.Unsupported("Screen questions are turned off in Settings.")
        val root = svc.targetRoot() ?: return ActionResult.Failure("I can't see the app on screen.")
        val text = svc.allNodes(root).filter { it.isVisibleToUser }.map { svc.labelOf(it) }.filter { it.length > 1 }.distinct().joinToString("\n")
        if (text.isBlank()) return ActionResult.Failure("There's no readable text on this screen.")
        val app = root.packageName?.toString()?.let { p -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() } ?: "an app"
        return runCatching { ActionResult.Success(planner.answer("The user is looking at $app and asks: \"${c.question}\". Answer using this screen text.", text)) }
            .getOrElse { ActionResult.Failure(it.message ?: "The AI couldn't answer.") }
    }

    // ---- Research / chats / mail --------------------------------------------------------------
    suspend fun research(c: Command.Research): ActionResult = runCatching { ActionResult.Success(planner.research(c.query, Lang.hinglish)) }
        .getOrElse { ActionResult.Failure(it.message ?: "I couldn't research that.") }

    private val chatPkgs = mapOf("whatsapp" to "com.whatsapp", "instagram" to "com.instagram.android", "telegram" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android", "discord" to "com.discord")

    suspend fun messages(c: Command.Messages): ActionResult {
        if (!SamiNotificationListener.connected) return ActionResult.Unsupported("I need notification access. Turn it on in Sami › Settings.")
        SamiNotificationListener.seed()
        var items = NotificationStore.recent(System.currentTimeMillis() - 24 * 3_600_000)
            .filter { it.pkg in chatPkgs.values || it.pkg == "com.google.android.apps.messaging" }
        c.app?.let { a -> chatPkgs[a]?.let { pkg -> items = items.filter { it.pkg == pkg } } }
        var who: String? = null
        if (c.person != null) {
            // Nickname → real contact name ("her" → your saved contact), then match the notification title.
            who = (contacts.find(c.person) as? ContactsRepo.Match.One)?.contact?.name ?: c.person
            val w = who!!.lowercase()
            items = items.filter { it.title.lowercase().contains(w) || it.title.lowercase().contains(w.split(" ").first()) }
        }
        val where = c.app?.replaceFirstChar { it.uppercase() } ?: "your chats"
        if (items.isEmpty()) return ActionResult.Success(Lang.t(
            if (who != null) "No new messages from $who in the last day." else "No new messages on $where in the last day.",
            if (who != null) "$who ka koi naya message nahi hai." else "$where pe koi naya message nahi hai."))
        val text = items.takeLast(40).joinToString("\n") { "[${it.app}] ${it.title}: ${it.text}" }
        if (!prefs.aiNotifications || prefs.groqKey.isNullOrBlank())
            return ActionResult.Success(items.takeLast(3).joinToString(". ") { "${it.title} said ${it.text}" })
        val ask = if (who != null) "Tell the user what $who said (quote briefly), and anything they need to reply to."
                  else "Summarize these new $where messages: who messaged, what they want, and what needs a reply. Mention urgent things first."
        return runCatching { ActionResult.Success(planner.answer(ask + (if (Lang.hinglish) " Reply in Hinglish." else ""), text)) }
            .getOrElse { ActionResult.Success(items.takeLast(3).joinToString(". ") { "${it.title}: ${it.text}" }) }
    }

    val gmail = com.sami.assistant.system.GmailClient(context)
    suspend fun mail(c: Command.Mail): ActionResult {
        if (!prefs.gmailConnected) return ActionResult.Unsupported(Lang.t("Connect Gmail in Sami › Settings first.", "Pehle Settings mein Gmail connect karo."))
        val token = when (val a = gmail.authorize()) {
            is com.sami.assistant.system.GmailClient.Auth.Token -> a.value
            is com.sami.assistant.system.GmailClient.Auth.NeedsConsent -> return ActionResult.Unsupported("Gmail needs you to sign in again. Open Settings › Connect Gmail.")
            is com.sami.assistant.system.GmailClient.Auth.Error -> return ActionResult.Failure("Gmail: ${a.message}")
        }
        // User preference: account security / sign-in notices are never "important updates".
        val noAlerts = " -from:accounts.google.com -from:no-reply@accounts.google.com -subject:(\"security alert\") -subject:(\"sign-in\") -subject:(\"new sign\")"
        val q = (c.query ?: "in:inbox newer_than:3d -category:promotions -category:social") + if (c.query == null) noAlerts else ""
        val mails = runCatching { gmail.search(token, q, if (c.query == null) 20 else 10) }.getOrElse { return ActionResult.Failure(it.message ?: "Gmail didn't respond.") }
        if (mails.isEmpty()) return ActionResult.Success(Lang.t("No matching mail.", "Koi aisa mail nahi mila."))
        val list = mails.joinToString("\n") { "${if (it.unread) "[UNREAD] " else ""}From ${it.from} | ${it.subject} | ${it.snippet.take(160)}" }
        val facts = prefs.facts.joinToString("; ")
        val ask = if (c.query == null) "These are the user's recent emails (a college student in India; $facts). Say which ones genuinely need attention " +
            "(deadlines, exams, assignments, placements/interviews, professors, fees) and why, in at most 3 sentences. " +
            "Never mention security alerts, sign-in notices or account-access emails. If nothing important, say so."
            else "Summarize these emails matching \"${c.query}\" for the user in at most 3 sentences."
        return runCatching { ActionResult.Success(planner.answer(ask + (if (Lang.hinglish) " Reply in Hinglish." else ""), list)) }
            .getOrElse { ActionResult.Success("You have ${mails.count { it.unread }} unread. Latest: ${mails.first().subject} from ${mails.first().from}.") }
    }

    // ---- Files & face photos ----------------------------------------------------------------
    val files = com.sami.assistant.system.FileFinder(context)
    val faces = com.sami.assistant.system.FaceIndex(context)

    private fun mimeOf(f: java.io.File) = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    val vault = com.sami.assistant.system.Vault(context)

    /** Opens a document the owner saved in Sami › My documents. */
    fun openSavedDoc(query: String): ActionResult? {
        val d = vault.findDoc(query) ?: return null
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.sami.assistant.files", d.file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(d.file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (launch(view)) ActionResult.Success(Lang.t("Here's your ${d.name}.", "Ye raha aapka ${d.name}.")) else ActionResult.Failure("I couldn't open ${d.name}.")
    }

    suspend fun openFile(c: Command.OpenFile): ActionResult {
        openSavedDoc(c.query)?.let { return it } // owner's saved documents first (instant, works from the overlay)
        if (!files.hasAccess()) {
            launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
            return ActionResult.HandedOff(Lang.t("Allow Sami “All files access”, then ask me again.", "Sami ko All files access do, phir dobara bolo."))
        }
        val hit = files.search(c.query) ?: return ActionResult.Failure(Lang.t("I couldn't find ${c.query} on your phone.", "${c.query} phone mein nahi mila."))
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.sami.assistant.files", hit.file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(hit.file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val how = if (hit.reason == "text") Lang.t(" (found by reading the file)", " (file padh ke dhoondha)") else ""
        return if (launch(view)) ActionResult.Success(Lang.t("Opening ${hit.file.name}$how.", "${hit.file.name} khol rahi hoon$how."))
        else ActionResult.Failure("No app on the phone can open ${hit.file.name}.")
    }

    suspend fun photoOf(c: Command.PhotoOf): ActionResult {
        // Owner-picked photos first: instant and always right.
        if (c.people.containsAll(setOf("me", "her")) || c.people == setOf("her")) {
            vault.ourPhotos().randomOrNull()?.let { uri ->
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (launch(view)) return ActionResult.Success(Lang.t("Here's one of you two.", "Ye lo, aap dono ki ek photo."))
            }
            if (faces.enrolledCounts().isEmpty()) return ActionResult.Unsupported(Lang.t("Add your photos together in Sami › Settings › Our photos first.",
                "Pehle Settings › Our photos mein apni photos add karo."))
        }
        val enrolled = faces.enrolledCounts()
        val missing = c.people.filter { (enrolled[it] ?: 0) == 0 }
        if (missing.isNotEmpty()) return ActionResult.Unsupported(Lang.t(
            "Teach me ${missing.joinToString(" and ") { if (it == "me") "your face" else "her face" }} first in Sami › Settings › Faces.",
            "Pehle Settings › Faces mein ${missing.joinToString(" aur ") { if (it == "me") "aapka" else "unka" }} chehra sikhao."))
        if (faces.countWith(c.people) == 0) faces.scan(250) // quick catch-up; the rest happens while charging
        val uri = faces.randomWith(c.people)
            ?: return ActionResult.Failure(Lang.t("I haven't found a photo like that yet. I've scanned ${faces.indexedCount()} photos; I'll keep scanning while you charge.",
                "Abhi tak aisi photo nahi mili. ${faces.indexedCount()} photos dekh li, charging pe aur dekhungi."))
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { if (installed("com.oneplus.gallery")) setPackage("com.oneplus.gallery") }
        return if (launch(view) || launch(view.setPackage(null))) ActionResult.Success(Lang.t("Here's one of you two.", "Ye lo, aap dono ki ek photo."))
        else ActionResult.Failure("The gallery didn't open.")
    }

    // ---- Timetable --------------------------------------------------------------------------
    fun classQuery(c: Command.ClassQuery): ActionResult {
        val tt = com.sami.assistant.system.Timetable
        val fmt = java.time.format.DateTimeFormatter.ofPattern("h:mm a")
        return when (c.kind) {
            Command.ClassKind.WHERE -> { val v = c.venue ?: return ActionResult.Failure("Which room?")
                ActionResult.Success(Lang.t("$v is on the ${tt.floorOf(v)} of the Academic Block.", "$v Academic Block ke ${tt.floorOf(v)} pe hai.")) }
            Command.ClassKind.NEXT -> { val (s, at) = tt.next() ?: return ActionResult.Success("No classes found.")
                val day = when (at.toLocalDate()) { java.time.LocalDate.now() -> "today"; java.time.LocalDate.now().plusDays(1) -> "tomorrow"; else -> at.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() } }
                ActionResult.Success(Lang.t("Next is ${tt.describe(s)}, $day at ${at.format(fmt)}.", "Agli class ${s.subject} hai, $day ${at.format(fmt)} baje, ${s.venue} (${tt.floorOf(s.venue)}).")) }
            Command.ClassKind.DAY -> { val d = java.time.LocalDate.now().plusDays(c.dayOffset.toLong()); val list = tt.on(d.dayOfWeek)
                val label = if (c.dayOffset == 0) Lang.t("today", "aaj") else Lang.t("tomorrow", "kal")
                if (list.isEmpty()) ActionResult.Success(Lang.t("No classes $label. Enjoy!", "$label koi class nahi hai."))
                else ActionResult.Success(Lang.t("Classes $label: ", "$label ki classes: ") + list.joinToString(". ") { "${it.start.format(fmt)} ${it.subject} in ${it.venue}" } + ".") }
        }
    }

    // ---- Sami's own settings ----------------------------------------------------------------
    var onVoiceChange: ((Float, Float) -> Unit)? = null

    fun samiSetting(c: Command.SamiSetting): ActionResult {
        val v = c.value
        fun onOff() = v == "on"
        fun step(cur: Float, by: Float, lo: Float, hi: Float): Float = when (v) {
            "+" -> cur + by; "-" -> cur - by
            else -> v.toFloatOrNull()?.let { it / 100f * (hi - lo) + lo } ?: cur
        }.coerceIn(lo, hi)
        val t = Lang::t
        return when (c.key) {
            Command.SettingKey.WAKE -> {
                prefs.wakeEnabled = onOff()
                if (onOff()) com.sami.assistant.system.WakeWordService.start(context) else com.sami.assistant.system.WakeWordService.stop(context)
                ActionResult.Success(if (onOff()) t("Wake word is on.", "Wake word on kar diya.") else t("Wake word is off. Use the power button or the tile to call me.", "Wake word band kar diya."))
            }
            Command.SettingKey.SLEEP_PAUSE -> {
                prefs.sleepPause = onOff()
                if (prefs.wakeEnabled) { com.sami.assistant.system.WakeWordService.stop(context); com.sami.assistant.system.WakeWordService.start(context) }
                ActionResult.Success(if (onOff()) t("I'll stop listening from ${prefs.sleepTime} to ${prefs.wakeTime}.", "Raat ${prefs.sleepTime} se ${prefs.wakeTime} tak wake word band rahega.")
                    else t("Night pause is off. I'll listen all night.", "Night pause band. Poori raat sunungi."))
            }
            Command.SettingKey.SENSITIVITY -> {
                prefs.sensitivity = step(prefs.sensitivity, 0.1f, 0.3f, 0.9f)
                if (prefs.wakeEnabled) { com.sami.assistant.system.WakeWordService.stop(context); com.sami.assistant.system.WakeWordService.start(context) }
                ActionResult.Success(t("Wake sensitivity is now ${(prefs.sensitivity * 100).toInt()}.", "Sensitivity ${(prefs.sensitivity * 100).toInt()} kar di."))
            }
            Command.SettingKey.CONTINUOUS -> { prefs.continuous = onOff(); ActionResult.Success(if (onOff()) t("Continuous conversation on.", "Continuous conversation on.") else t("Continuous conversation off.", "Continuous conversation band.")) }
            Command.SettingKey.SPEECH_RATE -> {
                prefs.ttsRate = step(prefs.ttsRate, 0.15f, 0.6f, 1.6f); onVoiceChange?.invoke(prefs.ttsRate, prefs.ttsPitch)
                ActionResult.Success(t("Okay, is this better?", "Theek hai, ab kaisa hai?"))
            }
            Command.SettingKey.PITCH -> {
                prefs.ttsPitch = step(prefs.ttsPitch, 0.1f, 0.6f, 1.5f); onVoiceChange?.invoke(prefs.ttsRate, prefs.ttsPitch)
                ActionResult.Success(t("Changed my voice. How's this?", "Awaaz badal di. Ab kaisi hai?"))
            }
            Command.SettingKey.REPLY_LANGUAGE -> {
                prefs.replyLanguage = v; Lang.hinglish = v == "hinglish"
                ActionResult.Success(when (v) { "english" -> "Okay, I'll reply in English."; "hinglish" -> "Theek hai, ab Hinglish mein baat karungi."; else -> "Okay, I'll match your language." })
            }
            Command.SettingKey.WAKE_TIME -> { prefs.wakeTime = v; ActionResult.Success(t("Got it, you wake up at $v. The good night routine will set that alarm.", "Theek hai, $v ka alarm good night pe lagaungi.")) }
            Command.SettingKey.SLEEP_TIME -> { prefs.sleepTime = v; ActionResult.Success(t("Night pause now starts at $v.", "Night pause ab $v se shuru hoga.")) }
            Command.SettingKey.AI_NOTIFICATIONS -> { prefs.aiNotifications = onOff(); ActionResult.Success(if (onOff()) "AI notification summaries on." else "AI notification summaries off.") }
            Command.SettingKey.AI_SCREEN -> { prefs.aiScreen = onOff(); ActionResult.Success(if (onOff()) "I can read the screen when you ask." else "I won't read the screen with AI.") }
        }
    }

    // ---- Routines & memory -----------------------------------------------------------------
    suspend fun routine(c: Command.Routine): ActionResult {
        val done = mutableListOf<String>()
        suspend fun step(cmd: Command, label: String) { val r = run(cmd); if (r is ActionResult.Success) done += label }
        when (c.name) {
            Command.RoutineName.GOOD_NIGHT -> {
                step(Command.Media(Command.MediaOp.PAUSE), "paused media")
                step(Command.DoNotDisturb(true), "Do Not Disturb on")
                step(Command.Brightness(percent = 10), "dimmed the screen")
                prefs.wakeTime?.let { t -> runCatching { java.time.LocalTime.parse(t) }.getOrNull() }?.let { t ->
                    step(Command.Alarm(t.hour, t.minute, "Good morning"), "alarm at ${t.format(DateTimeFormatter.ofPattern("h:mm a"))}")
                }
                val tail = if (prefs.wakeTime == null) " Tell me “remember I wake up at 7” and I'll set your alarm too." else ""
                return ActionResult.Success("Good night! ${if (done.isEmpty()) "I couldn't change anything." else "I ${done.joinToString(", ")}."}$tail")
            }
            Command.RoutineName.GOOD_MORNING -> {
                step(Command.DoNotDisturb(false), "turned off Do Not Disturb")
                step(Command.Brightness(percent = 60), "brightened the screen")
                val batt = battery().speech
                val missed = if (SamiNotificationListener.connected) " " + missed().speech else ""
                return ActionResult.Success("Good morning! ${done.joinToString(", ").replaceFirstChar { it.uppercase() }}. $batt$missed")
            }
        }
    }

    fun remember(c: Command.Remember): ActionResult {
        val fact = c.fact.trim().removeSuffix(".")
        prefs.facts = prefs.facts + fact
        // A wake-up time fact also feeds the Good night routine.
        Regex("""(?:wake|uth|utht)\w*\s+(?:up\s+)?(?:at\s+)?(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?""").find(fact.lowercase())?.let { m ->
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toIntOrNull() ?: 0
            if (m.groupValues[3] == "pm" && h < 12) h += 12
            if (h in 0..23) prefs.wakeTime = "%02d:%02d".format(h, min)
        }
        return ActionResult.Success("Got it, I'll remember that.")
    }

    fun forgetAll(): ActionResult { prefs.facts = emptyList(); prefs.wakeTime = null; return ActionResult.Success("Done. I've forgotten everything you told me.") }
}

/** Latest upload for a channel name, via YouTube's public pages and RSS feed (no API key). */
object YouTubeFeed {
    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000; readTimeout = 10000
            // Desktop UA: the mobile site (m.youtube.com) omits the channel id we need.
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36")
            setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            setRequestProperty("Cookie", "CONSENT=YES+1")
        }
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** Top search result for a query: (videoId, title). */
    suspend fun topVideo(query: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val html = get("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        val m = Regex(""""videoRenderer":\{"videoId":"([\w-]{11})".{0,1500}?"title":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"""", RegexOption.DOT_MATCHES_ALL).find(html) ?: return@withContext null
        m.groupValues[1] to m.groupValues[2].replace("\\u0026", "&").replace("\\\"", "\"")
    }

    /** @return (videoId, title) */
    suspend fun latestVideo(channel: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val handle = channel.lowercase().replace(Regex("""[^a-z0-9._-]"""), "")
        val channelId = runCatching { Regex(""""(?:channelId|externalId)":"(UC[\w-]{22})"""").find(get("https://www.youtube.com/@$handle"))?.groupValues?.get(1) }.getOrNull()
            ?: runCatching {
                // Channel-filtered search; the first channel renderer is the best match.
                Regex(""""channelRenderer":\{"channelId":"(UC[\w-]{22})"""").find(get("https://www.youtube.com/results?search_query=" + Uri.encode(channel) + "&sp=EgIQAg%253D%253D"))?.groupValues?.get(1)
            }.getOrNull()
            ?: return@withContext null
        val feed = get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
        val entry = feed.substringAfter("<entry>", "").ifEmpty { return@withContext null }
        val id = Regex("""<yt:videoId>([\w-]{11})</yt:videoId>""").find(entry)?.groupValues?.get(1) ?: return@withContext null
        val title = Regex("""<title>(.*?)</title>""").find(entry)?.groupValues?.get(1)?.let { android.text.Html.fromHtml(it, 0).toString() } ?: "the latest video"
        id to title
    }
}

package com.sami.assistant.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sami.assistant.SamiApp
import com.sami.assistant.core.AssistantController
import com.sami.assistant.system.Capability
import com.sami.assistant.system.PermissionCenter
import com.sami.assistant.system.WakeBus
import com.sami.assistant.system.WakeWordService

private val Card = Color(0x12FFFFFF)
private val Border = Color(0x1FFFFFFF)
private val Muted = Color(0xFF8A93B2)
private val Accent = Color(0xFF4F6BFF)
private val Ok = Color(0xFF3EE8A8)

@Composable
fun SettingsScreen(vm: AssistantController) {
    val ctx = LocalContext.current
    val prefs = remember { (ctx.applicationContext as SamiApp).container.prefs }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val caps = remember(refresh) { PermissionCenter.all(ctx) }

    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Section("Screen control")
        ScreenControlCard(refresh) { refresh++ }

        Section("Wake word")
        WakeWordCard(ctx, prefs, caps, onRefresh = { refresh++ })

        Section("Accounts")
        Box(Modifier.glass().padding(16.dp)) { GmailRow(prefs) }

        Section("Classes")
        ClassesCard()

        Section("Our photos")
        OurPhotosCard()

        Section("My documents")
        DocumentsCard()

        Section("AI brain")
        AiCard(prefs)

        Section("Permissions")
        caps.forEach { cap ->
            CapabilityRow(cap) {
                when {
                    cap.runtimePermission != null -> runtime.launch(cap.runtimePermission)
                    cap.settingsIntent != null -> openSettings(ctx, cap.settingsIntent)
                }
            }
        }

        Section("Voice")
        var rate by remember { mutableFloatStateOf(prefs.ttsRate) }
        var pitch by remember { mutableFloatStateOf(prefs.ttsPitch) }
        Box(Modifier.glass().padding(16.dp)) {
            Column {
                Text("Speaking rate  ${"%.2f".format(rate)}×", color = Color.White, fontSize = 14.sp)
                Slider(rate, { rate = it }, valueRange = 0.6f..1.6f, onValueChangeFinished = { vm.applyVoice(rate, pitch) }, colors = sliderColors())
                Text("Pitch  ${"%.2f".format(pitch)}", color = Color.White, fontSize = 14.sp)
                Slider(pitch, { pitch = it }, valueRange = 0.6f..1.5f, onValueChangeFinished = { vm.applyVoice(rate, pitch) }, colors = sliderColors())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Test voice") { vm.testVoice() }
                    SmallButton("Choose voice") { openSettings(ctx, Intent("com.android.settings.TTS_SETTINGS")) }
                }
            }
        }

        Section("Memory & payees")
        MemoryCard(prefs)

        Section("Quick activation")
        Text(
            "Without the wake word you can still start Sami from: the Sami tile in Quick Settings (edit your tiles and drag it in), " +
                "holding the power button or the assist gesture once Sami is your digital assistant, or by long-pressing the app icon › Talk to Sami.",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 32.dp)
        )
    }
}

@Composable
private fun WakeWordCard(ctx: Context, prefs: com.sami.assistant.core.SamiPrefs, caps: List<Capability>, onRefresh: () -> Unit) {
    val status by WakeBus.status.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(prefs.wakeEnabled) }
    var key by remember { mutableStateOf(prefs.picovoiceKey.orEmpty()) }
    var hasKeyword by remember { mutableStateOf(prefs.hasKeyword) }
    var sensitivity by remember { mutableFloatStateOf(prefs.sensitivity) }
    val micOk = caps.firstOrNull { it.id == "mic" }?.granted == true

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching {
            prefs.keywordFile.parentFile?.mkdirs()
            ctx.contentResolver.openInputStream(uri)!!.use { input -> prefs.keywordFile.outputStream().use { input.copyTo(it) } }
            hasKeyword = prefs.hasKeyword
        }
        onRefresh()
    }

    fun restartIfOn() { if (enabled) { WakeWordService.stop(ctx); WakeWordService.start(ctx) } }

    Box(Modifier.glass().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("“Hey Sami” detection", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(status, color = if (status.startsWith("Listening")) Ok else Muted, fontSize = 12.sp)
                }
                Switch(enabled, { on ->
                    enabled = on; prefs.wakeEnabled = on
                    if (on) WakeWordService.start(ctx) else WakeWordService.stop(ctx)
                }, enabled = micOk, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
            var sleepPause by remember { mutableStateOf(prefs.sleepPause) }
            ToggleRow("Pause while I sleep (${prefs.sleepTime}–${prefs.wakeTime ?: "07:00"})",
                "Saves battery overnight. Power button and Quick Settings tile still work.", sleepPause) {
                sleepPause = it; prefs.sleepPause = it
                if (enabled) { WakeWordService.stop(ctx); WakeWordService.start(ctx) } // apply now
            }
            Text(
                "Runs 100% on-device — no account needed. Say “Hey Sami” and Sami pops up. No audio leaves the phone until you speak a command. " +
                    "Optional: for lower battery use, add a Picovoice AccessKey and “Hey Sami” .ppn file below (console.picovoice.ai).",
                color = Muted, fontSize = 12.sp
            )
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22000000)).border(1.dp, Border, RoundedCornerShape(12.dp)).padding(12.dp)) {
                if (key.isEmpty()) Text("Picovoice AccessKey", color = Muted, fontSize = 14.sp)
                BasicTextField(key, { key = it.trim() }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton("Save key") { prefs.picovoiceKey = key; restartIfOn(); onRefresh() }
                SmallButton(if (hasKeyword) "Replace .ppn" else "Choose .ppn file") { picker.launch(arrayOf("*/*")) }
                if (hasKeyword) Text("✓ keyword", color = Ok, fontSize = 12.sp)
            }
            Text("Sensitivity ${"%.2f".format(sensitivity)} — higher wakes more easily but triggers falsely more", color = Muted, fontSize = 12.sp)
            Slider(sensitivity, { sensitivity = it }, valueRange = 0.3f..0.9f, onValueChangeFinished = { prefs.sensitivity = sensitivity; restartIfOn() }, colors = sliderColors())
            if (!micOk) Text("Grant Microphone below first.", color = Color(0xFFFFB84D), fontSize = 12.sp)
        }
    }
}

@Composable
private fun CapabilityRow(cap: Capability, onGrant: () -> Unit) {
    Row(Modifier.padding(bottom = 10.dp).glass().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(cap.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(cap.why, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (cap.granted) Text("On", color = Ok, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        else SmallButton("Enable", onGrant)
    }
}

@Composable private fun Section(t: String) =
    Text(t.uppercase(), color = Muted, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))

@Composable private fun SmallButton(t: String, onClick: () -> Unit) =
    Box(Modifier.clip(RoundedCornerShape(50)).background(Accent.copy(alpha = 0.25f)).border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) { Text(t, color = Color.White, fontSize = 13.sp) }

@Composable private fun sliderColors() = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color(0x33FFFFFF))

private fun Modifier.glass() = fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, Border, RoundedCornerShape(20.dp))

private fun openSettings(ctx: Context, intent: Intent) {
    try { ctx.startActivity(intent) } catch (_: ActivityNotFoundException) { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}


@Composable
private fun AiCard(prefs: com.sami.assistant.core.SamiPrefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(prefs.groqKey.orEmpty()) }
    var model by remember { mutableStateOf(prefs.groqModel) }
    var status by remember { mutableStateOf(if (prefs.groqKey.isNullOrBlank()) "Not set — basic commands still work offline" else "Key saved") }
    var notif by remember { mutableStateOf(prefs.aiNotifications) }
    var screen by remember { mutableStateOf(prefs.aiScreen) }
    Box(Modifier.glass().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Groq (free)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("Used only when Sami can't understand a request on its own. Volume, apps, media, alarms, calls and payments run locally without AI. " +
                "Get a free key at console.groq.com › API Keys.", color = Muted, fontSize = 12.sp)
            Field(key, "Groq API key (gsk_…)", password = true) { key = it.trim() }
            Field(model, "Model") { model = it.trim() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Save & test") {
                    prefs.groqKey = key; prefs.groqModel = model.ifBlank { "openai/gpt-oss-120b" }
                    status = "Testing…"
                    scope.launch {
                        val c = (ctx.applicationContext as SamiApp).container
                        status = try {
                            val r = c.groq.chat(listOf(com.sami.assistant.ai.GroqClient.Msg("user", "Reply with the single word: ok")), json = false, maxTokens = 50)
                            "✓ Connected (${prefs.groqModel}) — replied “${r.take(20)}”"
                        } catch (e: Exception) {
                            val models = runCatching { c.groq.models() }.getOrDefault(emptyList())
                            "✗ ${e.message}" + if (models.isNotEmpty()) "\nAvailable: ${models.take(8).joinToString()}" else ""
                        }
                    }
                }
            }
            Text(status, color = if (status.startsWith("✓")) Ok else Muted, fontSize = 12.sp)
            ToggleRow("Let AI summarize notifications", "Sends notification text to Groq only when you ask “what did I miss?”", notif) { notif = it; prefs.aiNotifications = it }
            ToggleRow("Let AI read the screen when asked", "For “what's this?” / “summarize this page”. Only on request.", screen) { screen = it; prefs.aiScreen = it }
        }
    }
}

@Composable
private fun MemoryCard(prefs: com.sami.assistant.core.SamiPrefs) {
    var aliases by remember { mutableStateOf(prefs.aliases) }
    var learned by remember { mutableStateOf(prefs.learned) }
    var plans by remember { mutableStateOf(prefs.planCache.size) }
    var newAlias by remember { mutableStateOf("") }
    var aliasError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    Box(Modifier.glass().padding(16.dp).padding(bottom = 4.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nicknames", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("What you say → the exact contact name. Used for calls, WhatsApp and payments.", color = Muted, fontSize = 12.sp)
            aliases.entries.groupBy({ it.value }, { it.key }).forEach { (who, nicks) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(who, color = Color.White, fontSize = 13.sp)
                        Text(nicks.joinToString(", "), color = Muted, fontSize = 12.sp)
                    }
                    Text("Edit", color = Accent, fontSize = 12.sp, modifier = Modifier.clickable {
                        // Load into the editor; saving replaces this person's nicknames.
                        newAlias = "${nicks.joinToString(", ")} = $who"; editing = who; aliasError = null
                    }.padding(6.dp))
                    Text("Remove", color = Color(0xFFFF8FA3), fontSize = 12.sp, modifier = Modifier.clickable {
                        aliases = aliases.filterValues { it != who }; prefs.aliases = aliases
                    }.padding(6.dp))
                }
            }
            Field(newAlias, "bhai, brother = Rohan Sharma") { newAlias = it; aliasError = null }
            aliasError?.let { Text(it, color = Color(0xFFFF8FA3), fontSize = 12.sp) }
            SmallButton(if (editing != null) "Save changes" else "Add nickname") {
                val parts = newAlias.split("=")
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) aliasError = "Use: nickname = Contact Name"
                else {
                    val target = parts[1].trim()
                    val base = editing?.let { old -> aliases.filterValues { it != old } } ?: aliases
                    aliases = base + parts[0].split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.associateWith { target }
                    prefs.aliases = aliases; newAlias = ""; editing = null
                }
            }
            Text("Learned", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            Text("Say “no, I meant …” or “galat, mera matlab …” after a mistake and Sami remembers. $plans AI answers are saved to run offline.", color = Muted, fontSize = 12.sp)
            learned.entries.toList().takeLast(8).forEach { (k, v) -> Text("“$k” → “$v”", color = Color.White, fontSize = 12.sp) }
            if (learned.isNotEmpty() || plans > 0) SmallButton("Reset learning") { prefs.learned = emptyMap(); prefs.planCache = emptyMap(); learned = emptyMap(); plans = 0 }
        }
    }
    Spacer(Modifier.height(10.dp))
    MemoryFacts(prefs)
}

@Composable
private fun MemoryFacts(prefs: com.sami.assistant.core.SamiPrefs) {
    var facts by remember { mutableStateOf(prefs.facts) }
    var payees by remember { mutableStateOf(prefs.payees) }
    Box(Modifier.glass().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Say “remember that …” to teach Sami. Stored encrypted on this phone.", color = Muted, fontSize = 12.sp)
            if (facts.isEmpty()) Text("Nothing remembered yet", color = Muted, fontSize = 13.sp)
            facts.forEach { f -> Text("• $f", color = Color.White, fontSize = 13.sp) }
            if (facts.isNotEmpty()) SmallButton("Forget all") { prefs.facts = emptyList(); prefs.wakeTime = null; facts = emptyList() }
            Text("Saved UPI IDs: ${payees.size}", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            if (payees.isNotEmpty()) SmallButton("Clear saved UPI IDs") { prefs.payees = emptyMap(); payees = emptyMap() }
        }
    }
}

@Composable
private fun Field(value: String, hint: String, password: Boolean = false, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22000000)).border(1.dp, Border, RoundedCornerShape(12.dp)).padding(12.dp)) {
        if (value.isEmpty()) Text(hint, color = Muted, fontSize = 14.sp)
        BasicTextField(value, onChange, singleLine = true, visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontSize = 14.sp); Text(sub, color = Muted, fontSize = 11.sp) }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
    }
}


@Composable
private fun GmailRow(prefs: com.sami.assistant.core.SamiPrefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(prefs.gmailConnected) }
    var status by remember { mutableStateOf(if (prefs.gmailConnected) "Connected (read-only)" else "Not connected") }
    val gmail = remember { com.sami.assistant.system.GmailClient(ctx) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) { prefs.gmailConnected = true; connected = true; status = "✓ Connected (read-only)" }
        else status = "Sign-in cancelled"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Gmail", color = Color.White, fontSize = 14.sp)
            Text("“Any important mail?” Read-only; nothing is stored. $status", color = if (status.startsWith("✓")) Ok else Muted, fontSize = 11.sp)
        }
        if (connected) Text("Disconnect", color = Color(0xFFFF8FA3), fontSize = 12.sp, modifier = Modifier.clickable { prefs.gmailConnected = false; connected = false; status = "Not connected" }.padding(6.dp))
        else SmallButton("Connect") {
            status = "Opening Google sign-in…"
            scope.launch {
                when (val a = gmail.authorize()) {
                    is com.sami.assistant.system.GmailClient.Auth.NeedsConsent -> consent.launch(androidx.activity.result.IntentSenderRequest.Builder(a.intent.intentSender).build())
                    is com.sami.assistant.system.GmailClient.Auth.Token -> { prefs.gmailConnected = true; connected = true; status = "✓ Connected (read-only)" }
                    is com.sami.assistant.system.GmailClient.Auth.Error -> status = "✗ ${a.message}. Finish the Google Cloud setup (docs/GMAIL_SETUP.md)."
                }
            }
        }
    }
}


/** Teach Sami two faces ("Me", "Her") from your own photos. Everything stays on the phone. */
@Composable
private fun FacesCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val faces = remember { (ctx.applicationContext as SamiApp).container.actions.personal.faces }
    var counts by remember { mutableStateOf(faces.enrolledCounts()) }
    var pending by remember { mutableStateOf<List<com.sami.assistant.system.FaceIndex.FaceCrop>>(emptyList()) }
    var status by remember { mutableStateOf("Scanned ${faces.indexedCount()} photos · ${faces.countWith(setOf("me", "her"))} of you two") }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true; status = "Finding faces…"
        scope.launch {
            val found = mutableListOf<com.sami.assistant.system.FaceIndex.FaceCrop>()
            for (u in uris) {
                val bmp = runCatching { ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) } }.getOrNull() ?: continue
                found += faces.faces(bmp)
            }
            pending = found; busy = false
            status = if (found.isEmpty()) "No faces found in those photos." else "Tap who each face is."
        }
    }
    Box(Modifier.glass().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("“Open a photo of me and her”", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Pick a few photos of you and her (selfies together work great), then tap who each face is. 3–5 faces each is ideal. " +
                "Face recognition runs only on this phone; nothing is uploaded.", color = Muted, fontSize = 12.sp)
            Text("You: ${counts["me"] ?: 0} faces · Her: ${counts["her"] ?: 0} faces", color = Color.White, fontSize = 13.sp)
            pending.firstOrNull()?.let { face ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.foundation.Image(face.bitmap.asImageBitmap(), null, Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallButton("Me") { faces.addSample("me", face.embedding); counts = faces.enrolledCounts(); pending = pending.drop(1) }
                            SmallButton("Her") { faces.addSample("her", face.embedding); counts = faces.enrolledCounts(); pending = pending.drop(1) }
                            SmallButton("Skip") { pending = pending.drop(1) }
                        }
                        Text("${pending.size} face${if (pending.size > 1) "s" else ""} left", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(if (busy) "Working…" else "Pick photos") { if (!busy) picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                SmallButton("Scan gallery now") {
                    if (!busy) { busy = true; status = "Scanning… (keep Sami open)"
                        scope.launch { var total = 0; repeat(4) { total += faces.scan(150) }; busy = false
                            status = "Scanned ${faces.indexedCount()} photos · ${faces.countWith(setOf("me", "her"))} of you two" } }
                }
            }
            Text(status, color = Muted, fontSize = 12.sp)
            Text("Sami also scans new photos automatically while the phone charges.", color = Muted, fontSize = 11.sp)
            if ((counts["me"] ?: 0) + (counts["her"] ?: 0) > 0)
                Text("Forget faces", color = Color(0xFFFF8FA3), fontSize = 12.sp, modifier = Modifier.clickable { faces.reset(); counts = emptyMap(); status = "Faces forgotten." }.padding(4.dp))
        }
    }
}


@Composable
private fun ClassesCard() {
    val ctx = LocalContext.current
    val notifOk = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { com.sami.assistant.system.Timetable.scheduleAll(ctx) }
    Box(Modifier.glass().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Class reminders: ${com.sami.assistant.system.Timetable.LEAD_MIN} min before, with room and floor", color = Color.White, fontSize = 14.sp)
            val fmt = java.time.format.DateTimeFormatter.ofPattern("h:mm a")
            com.sami.assistant.system.Timetable.next()?.let { (slot, at) ->
                Text("Next: ${slot.subject} · ${at.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} ${at.format(fmt)} · ${slot.venue} (${com.sami.assistant.system.Timetable.floorOf(slot.venue)})", color = Muted, fontSize = 12.sp)
            }
            Text("Ask: “next class?”, “kal ki classes?”, “where is CR-8?”", color = Muted, fontSize = 12.sp)
            if (!notifOk) { Text("Turn on notifications so reminders can show.", color = Color(0xFFFFB84D), fontSize = 12.sp)
                SmallButton("Allow notifications") { ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }
        }
    }
}

@Composable
private fun OurPhotosCard() {
    val ctx = LocalContext.current
    val vault = remember { (ctx.applicationContext as SamiApp).container.actions.personal.vault }
    var count by remember { mutableIntStateOf(vault.ourPhotos().size) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) { vault.addOurPhotos(uris); count = vault.ourPhotos().size }
    }
    Box(Modifier.glass().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("“Open a photo of me and her”", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Pick as many photos of you two as you like. Sami opens a random one each time — instantly. Stays on this phone.", color = Muted, fontSize = 12.sp)
            Text("$count photo${if (count == 1) "" else "s"} saved", color = Color.White, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Add photos") { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                if (count > 0) Text("Clear all", color = Color(0xFFFF8FA3), fontSize = 12.sp, modifier = Modifier.clickable { vault.clearOurPhotos(); count = 0 }.padding(8.dp))
            }
        }
    }
}

@Composable
private fun DocumentsCard() {
    val ctx = LocalContext.current
    val vault = remember { (ctx.applicationContext as SamiApp).container.actions.personal.vault }
    var docs by remember { mutableStateOf(vault.docs()) }
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { if (vault.addDoc(name, uri) != null) { docs = vault.docs(); name = "" } else err = "Couldn't save that photo." }
    }
    Box(Modifier.glass().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aadhaar, PAN, uni ID… say “open my aadhaar” and Sami shows it — also from the overlay.", color = Muted, fontSize = 12.sp)
            docs.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("Remove", color = Color(0xFFFF8FA3), fontSize = 12.sp, modifier = Modifier.clickable { vault.removeDoc(d); docs = vault.docs() }.padding(6.dp))
                }
            }
            Field(name, "Name, e.g. Aadhaar card / PAN card / Uni ID card") { name = it; err = null }
            err?.let { Text(it, color = Color(0xFFFF8FA3), fontSize = 12.sp) }
            SmallButton("Add photo of document") {
                if (name.isBlank()) err = "Type a name first" else picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            Text("Saved privately inside Sami (not in your gallery). Never sent to the AI.", color = Muted, fontSize = 11.sp)
        }
    }
}


/** Top-of-Settings switch for Sami's accessibility service (screen control). */
@Composable
private fun ScreenControlCard(refreshKey: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val on = remember(refreshKey) { PermissionCenter.a11yEnabled(ctx) }
    val auto = remember(refreshKey) { com.sami.assistant.system.PaymentGuard.canSelfRestore(ctx) }
    Box(Modifier.glass().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Screen control", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(if (on) "On — back, scroll, tap, WhatsApp send" else "Off", color = if (on) Ok else Muted, fontSize = 12.sp)
                }
                Switch(on, { want ->
                    if (!com.sami.assistant.system.PaymentGuard.setEnabled(ctx, want)) com.sami.assistant.system.PaymentGuard.openDetails(ctx)
                    onChanged()
                }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
            }
            Text(if (auto) "Turns off by itself in UPI/banking apps and back on when you leave."
                 else "Turns off by itself in UPI/banking apps. Fastest way to turn it back on: add the “Sami screen control” tile in Quick Settings " +
                   "(pull down › edit ✎ › drag it in) — one tap toggles it.",
                color = Muted, fontSize = 12.sp)
        }
    }
}

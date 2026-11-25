package com.sami.assistant.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.util.Log
import com.sami.assistant.actions.ActionResult
import com.sami.assistant.actions.ConfirmCard
import com.sami.assistant.ai.GroqClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import com.sami.assistant.commands.Command
import com.sami.assistant.commands.CommandParser
import com.sami.assistant.system.WakeBus
import com.sami.assistant.voice.SpeechEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orchestrates one conversation: listen → parse (Path A) → execute → respond,
 * then optionally keeps listening (continuous mode) until silence or "stop".
 * Shared by the Siri-style overlay and the full app so history is continuous.
 */
class AssistantController(private val c: AppContainer) {
    /** Process-wide scope: the conversation outlives whichever screen (overlay or full app) shows it. */
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AssistantUiState(continuous = c.prefs.continuous))
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var session: Job? = null

    init { c.actions.personal.onVoiceChange = { r, p -> c.speechOut.rate = r; c.speechOut.pitch = p } }
    private var turns = 0

    fun toggleContinuous() {
        _state.update { it.copy(continuous = !it.continuous) }
        c.prefs.continuous = _state.value.continuous
    }

    fun applyVoice(rate: Float, pitch: Float) {
        c.prefs.ttsRate = rate; c.prefs.ttsPitch = pitch
        c.speechOut.rate = rate; c.speechOut.pitch = pitch
    }

    fun testVoice() { viewModelScope.launch { c.speechOut.speak("Hi, I'm Sami. Main aapki kya madad kar sakti hoon?") } }

    /** Tap on orb: start, or interrupt whatever is happening. */
    fun onOrbTap() { if (session?.isActive == true) cancel() else startSession() }

    fun startSession() {
        session?.cancel()
        c.speechOut.stop()
        turns = 0
        WakeBus.assistantActive.value = true // wake engine releases the mic
        session = viewModelScope.launch {
            try {
                _state.update { it.copy(orb = OrbState.WAKE, transcript = "", response = "", actionLabel = "") }
                delay(350) // wake flourish; also gives the wake engine time to release the mic
                var again = true
                while (again) again = listenOnce()
            } finally {
                _state.update { it.copy(orb = OrbState.IDLE, level = 0f) }
                WakeBus.assistantActive.value = false
            }
        }
    }

    fun cancel() {
        pending?.complete(null)
        session?.cancel(); c.speechOut.stop()
        _state.update { it.copy(orb = OrbState.IDLE, level = 0f) }
        WakeBus.assistantActive.value = false
    }

    /** Typed command path. */
    fun submitText(text: String) {
        session?.cancel()
        session = viewModelScope.launch { handle(text) }
    }

    /** @return true if the conversation should continue listening. */
    private suspend fun listenOnce(): Boolean {
        if (!c.speechIn.isAvailable()) { fail("Speech recognition isn't available on this phone."); return false }
        _state.update { it.copy(orb = OrbState.LISTENING, transcript = "", level = 0f) }
        var final: String? = null
        var error: SpeechEvent.Error? = null
        c.speechIn.listen().collect { e ->
            when (e) {
                is SpeechEvent.Level -> _state.update { it.copy(level = e.value) }
                is SpeechEvent.Partial -> _state.update { it.copy(transcript = e.text) }
                is SpeechEvent.Final -> final = e.text
                is SpeechEvent.Error -> error = e
                else -> {}
            }
        }
        val text = final
        if (text == null) {
            val err = error
            // Silence during a follow-up turn just ends the conversation quietly.
            if (turns > 0 && err != null && err.code in listOf(6, 7)) return false
            fail(err?.message ?: "I didn't catch that."); return false
        }
        turns++
        return handle(text) && _state.value.continuous
    }

    // ---- Learning ----------------------------------------------------------------------------
    private var lastUserText: String? = null
    /** Set after "galat"/"wrong" with no fix: the next utterance is what the user meant. */
    private var awaitingCorrectionFor: String? = null
    private val recentActions = ArrayDeque<String>()
    private fun key(t: String) = CommandParser.normalize(t)

    private val correctionRe = Regex("""^(?:no+|nahi+n?|nope|galat|wrong|not that|that'?s wrong|ye nahi|yeh nahi)\b[\s,!.]*(?:(?:i meant|i said|mera matlab(?: tha)?|matlab|i want(?:ed)?|bola tha|kaha tha)\s*)?(.*)$""")

    private fun learn(wrong: String, meant: String) {
        if (key(wrong) == key(meant) || key(meant).isBlank()) return
        c.prefs.learned = c.prefs.learned + (key(wrong) to meant.trim())
        c.prefs.planCache = c.prefs.planCache - key(wrong) // forget the plan that was wrong
        Log.i("Sami", "learned '${key(wrong)}' -> '$meant'")
    }

    /** @return false when the conversation should end. */
    private suspend fun handle(spoken: String): Boolean {
        Lang.decide(spoken, c.prefs.replyLanguage)
        _state.update { it.copy(transcript = spoken, history = it.history + Turn(true, spoken), orb = OrbState.THINKING, level = 0f) }

        // 1) Corrections: "no, I meant call papa" / "galat, mera matlab ..." → do it and remember.
        var text = spoken
        awaitingCorrectionFor?.let { prev -> awaitingCorrectionFor = null; learn(prev, spoken) }
        val prev = lastUserText
        correctionRe.find(key(spoken))?.let { m ->
            if (prev != null) {
                val meant = m.groupValues[1].trim()
                if (meant.isEmpty()) {
                    awaitingCorrectionFor = prev
                    respond(Lang.t("Sorry about that. What did you mean?", "Sorry! Aapka matlab kya tha?"), "learning"); return true
                }
                learn(prev, meant); text = meant
                respond(Lang.t("Got it, I'll remember that.", "Samajh gayi, yaad rakhungi."), "learned")
            }
        }
        // 2) Apply what Sami learned earlier.
        c.prefs.learned[key(text)]?.let { Log.i("Sami", "using learned '$it' for '$text'"); text = it }
        lastUserText = text

        // 3) Path A: local rules — instant, offline, no AI.
        val local = CommandParser.parse(text)
        Log.i("Sami", "parsed '$text' -> $local")
        if (local is Command.Stop) { respond(Lang.t("Okay.", "Theek hai."), null); return false }

        // "do X and Y" / "X aur Y" / "X phir Y": local rules only see one intent, so let the AI plan both.
        val compound = Regex("""\s(and|aur|then|phir|fir|and then)\s""").containsMatchIn(text.lowercase()) &&
            local !is Command.WhatsApp && local !is Command.Remember && local !is Command.Reminder &&
            local !is Command.PlayMusic && local !is Command.YouTubeSearch && local !is Command.ShopSearch &&
            !c.prefs.groqKey.isNullOrBlank()

        // "open <something>" that isn't an app might be a saved document; otherwise let the AI work out what was meant.
        var localCmd: Command = local
        if (local is Command.OpenApp && com.sami.assistant.apps.AppMatcher.match(local.appName, c.apps.launchableApps()) == null) {
            c.actions.personal.openSavedDoc(local.appName)?.let { respond(it.speech, statusOf(it)); return false }
            if (!c.prefs.groqKey.isNullOrBlank()) localCmd = Command.Unknown(text)
        }

        val commands: List<Command>
        var rawPlan: String? = null
        if (localCmd !is Command.Unknown && !compound) commands = listOf(localCmd)
        else {
            // 4) Phrases the AI already solved run offline from the cache (no network, no battery cost).
            val cached = c.prefs.planCache[key(text)]?.let { com.sami.assistant.ai.PlanParser.parse(it, java.time.LocalDateTime.now()) }
            if (cached != null && cached.commands.isNotEmpty()) {
                Log.i("Sami", "plan cache hit: ${cached.commands}")
                commands = cached.commands
            } else {
                // 5) Path B: ask the AI, only for a validated action plan.
                if (c.prefs.groqKey.isNullOrBlank()) {
                    respond(Lang.t("I didn't get that. Add a free Groq key in Settings and I'll handle requests like this.",
                        "Ye samajh nahi aaya. Settings mein Groq key daal do, phir ye bhi kar dungi."), "not-understood")
                    return true
                }
                val plan = try {
                    c.planner.plan(text, _state.value.history.dropLast(1).map { it.fromUser to it.text },
                        com.sami.assistant.ai.Planner.Context(c.prefs.facts, c.apps.launchableApps().map { it.label }, c.prefs.aliases,
                            c.prefs.learned, c.actions.personal.lastContact, Lang.hinglish, recentActions.toList()),
                        java.time.LocalDateTime.now())
                } catch (e: GroqClient.AiException) { fail(e.message ?: "The AI is unavailable."); return false }
                catch (e: Exception) { Log.e("Sami", "planner", e); fail("The AI couldn't plan that."); return false }
                Log.i("Sami", "AI plan: ${plan.commands} say='${plan.say}'")
                if (plan.commands.isEmpty()) { respond(plan.say.ifBlank { Lang.t("I'm not sure how to do that.", "Ye kaise karun, pata nahi.") }, "ai"); return true }
                commands = plan.commands
                rawPlan = plan.raw
            }
        }

        var keepListening = true
        var allOk = true
        for ((i, command) in commands.withIndex()) {
            if (i > 0) delay(700) // let the previous app/screen settle
            val result = runOne(command)
            recentActions.addLast("${label(command)} → ${statusOf(result)}"); while (recentActions.size > 6) recentActions.removeFirst()
            if (result is ActionResult.Failure) {
                allOk = false
                // Smart retry: a local rule matched but failed (app/contact/file not found) → ask the AI once, with the reason.
                if (rawPlan == null && commands.size == 1 && !c.prefs.groqKey.isNullOrBlank() &&
                    command !is Command.Pay && command !is Command.Call && command !is Command.WhatsApp) {
                    val retry = runCatching {
                        c.planner.plan("$text\n(Note: my first attempt '${label(command)}' failed: ${result.speech} Pick a better action, or answer.)",
                            _state.value.history.dropLast(1).map { it.fromUser to it.text },
                            com.sami.assistant.ai.Planner.Context(c.prefs.facts, c.apps.launchableApps().map { it.label }, c.prefs.aliases,
                                c.prefs.learned, c.actions.personal.lastContact, Lang.hinglish, recentActions.toList()), java.time.LocalDateTime.now())
                    }.getOrNull()
                    val alt = retry?.commands?.firstOrNull { it != command }
                    if (alt != null) {
                        val r2 = runOne(alt)
                        if (r2 !is ActionResult.Failure) { respond(r2.speech, statusOf(r2)); return false }
                    } else if (retry != null && retry.commands.isEmpty() && retry.say.isNotBlank()) { respond(retry.say, "ai"); return true }
                }
                fail(result.speech); break
            }
            if (result is ActionResult.Unsupported) allOk = false
            respond(result.speech, statusOf(result))
            keepListening = keepListening && when (command) {
                is Command.OpenApp, is Command.YouTubeSearch, is Command.YouTubeLatest, is Command.YouTubePlay, is Command.PlayMusic, is Command.OpenFile, is Command.PhotoOf, is Command.Photos, Command.Home,
                is Command.OpenSettings, is Command.Screen, is Command.InstagramProfile, is Command.ShopSearch, is Command.Pay,
                is Command.Call, is Command.WhatsApp -> false
                else -> result !is ActionResult.HandedOff
            }
        }
        // Remember plans that worked (device actions only — live answers change, so never cached).
        if (allOk && rawPlan != null && commands.none { it is Command.WebAnswer || it is Command.Research || it is Command.Say || it is Command.Pay || it is Command.Messages || it is Command.Mail })
            c.prefs.planCache = c.prefs.planCache + (key(text) to rawPlan)
        return keepListening && allOk
    }

    private fun statusOf(r: ActionResult) = when (r) {
        is ActionResult.Success -> "done"; is ActionResult.HandedOff -> "opened"; is ActionResult.Unsupported -> "unsupported"
        is ActionResult.Failure -> "failed"; is ActionResult.NeedsConfirmation -> "confirm"
    }

    private suspend fun runOne(command: Command): ActionResult {
        _state.update { it.copy(orb = OrbState.EXECUTING, actionLabel = label(command)) }
        if (command is Command.Screen && command != Command.ReadScreen && command != Command.Global(Command.GlobalOp.LOCK)) {
            // Screen commands act on the app *under* Sami, so step aside first.
            _events.send(UiEvent.GetOutOfTheWay)
            delay(700)
        }
        val result = c.actions.execute(command)
        if (result !is ActionResult.NeedsConfirmation) return result
        val input = confirm(result.card) ?: return ActionResult.Unsupported("Okay, cancelled.")
        _state.update { it.copy(orb = OrbState.EXECUTING) }
        return try { result.proceed(input.ifEmpty { null }) } catch (e: Exception) { Log.e("Sami", "confirmed action", e); ActionResult.Failure("That didn't work.") }
    }

    // ---- Confirmation cards ---------------------------------------------------------------
    private var pending: CompletableDeferred<String?>? = null

    /** Called by the UI: confirmed (after biometrics, if required) with optional typed input, or cancelled. */
    fun answerConfirm(confirmed: Boolean, input: String? = null) {
        pending?.complete(if (confirmed) input.orEmpty() else null)
    }

    /** @return the typed input ("" if none) when confirmed, or null when cancelled / timed out. */
    private suspend fun confirm(card: ConfirmCard): String? = coroutineScope {
        val d = CompletableDeferred<String?>(); pending = d
        _state.update { it.copy(orb = OrbState.CONFIRMATION, confirm = card, response = card.spoken) }
        try {
            c.speechOut.speak(card.spoken)
            delay(400) // let the speaker go quiet so the mic doesn't hear Sami
            // Voice "yes/haan" for low-friction confirmations; payments always need a tap + fingerprint.
            val voice = if (card.voiceConfirm) launch {
                var heard: String? = null
                c.speechIn.listen().collect { e -> if (e is SpeechEvent.Final) heard = e.text.lowercase() }
                val h = heard ?: return@launch
                when {
                    // Whole-utterance match only, so stray speech (or Sami's own voice) can't confirm.
                    Regex("""^(yes|yes please|yeah|haan|haan ji|ha ji|confirm|confirmed|kar do)$""").matches(h.trim().trimEnd('.', '!')) -> d.complete("")
                    Regex("""^(no|nahi|nahin|cancel|mat karo|ruko|stop)$""").matches(h.trim().trimEnd('.', '!')) -> d.complete(null)
                }
            } else null
            val answer = withTimeoutOrNull(45_000) { d.await() }
            voice?.cancel()
            answer
        } finally {
            pending = null
            _state.update { it.copy(confirm = null) }
        }
    }

    private suspend fun respond(text: String, status: String?) {
        _state.update { it.copy(orb = OrbState.RESPONDING, response = text, history = it.history + Turn(false, text, status)) }
        c.speechOut.speak(text)
    }

    private suspend fun fail(text: String) {
        _state.update { it.copy(orb = OrbState.ERROR, response = text, history = it.history + Turn(false, text, "error")) }
        c.speechOut.speak(text)
        delay(600)
    }

    private fun label(c: Command) = when (c) {
        is Command.OpenApp -> "Opening ${c.appName}"
        is Command.Volume -> "Adjusting volume"
        is Command.VolumeSet -> "Volume → ${c.percent}%"
        is Command.Media -> "Media · ${c.op.name.lowercase()}"
        is Command.Flashlight -> if (c.on) "Flashlight on" else "Flashlight off"
        is Command.YouTubeSearch -> "YouTube · ${c.query}"
        is Command.OpenSettings -> "Settings · ${c.page.name.lowercase()}"
        is Command.Brightness -> "Brightness"
        is Command.DoNotDisturb -> if (c.on) "Do Not Disturb on" else "Do Not Disturb off"
        Command.Home -> "Going home"
        is Command.Global -> c.op.name.lowercase().replace('_', ' ')
        is Command.Scroll -> "Scrolling ${c.dir.name.lowercase()}"
        is Command.Tap -> if (c.ordinal != null) "Tapping item ${c.ordinal}" else "Tapping “${c.target}”"
        Command.ReadScreen -> "Reading screen"
        is Command.ScreenAsk -> "Looking at your screen"
        is Command.Pay -> "Preparing ₹${c.amountRupees.toLong()} to ${c.name}"
        is Command.Call -> "Calling ${c.name}"
        is Command.WhatsApp -> "WhatsApp · ${c.name}"
        is Command.Alarm -> "Alarm %d:%02d".format(c.hour, c.minute)
        is Command.Timer -> "Timer"
        is Command.Reminder -> "Reminder"
        is Command.Photos -> "Finding photos"
        is Command.YouTubeLatest -> "Latest from ${c.channel}"
        is Command.YouTubePlay -> "YouTube · ${c.query}"
        is Command.PlayMusic -> "Playing ${c.query}"
        is Command.InstagramProfile -> "Instagram · @${c.username}"
        is Command.ShopSearch -> "${c.store.name.lowercase()} · ${c.query}"
        Command.Missed -> "Checking notifications"
        is Command.Routine -> if (c.name == Command.RoutineName.GOOD_NIGHT) "Good night routine" else "Good morning routine"
        is Command.Remember -> "Remembering"
        Command.Battery -> "Battery"
        is Command.ScreenTime -> "Screen time"
        is Command.Research -> "Researching online…"
        is Command.WebAnswer -> "Searching the web…"
        is Command.Messages -> if (c.person != null) "Messages from ${c.person}" else "Checking messages"
        is Command.Mail -> "Checking Gmail"
        is Command.SamiSetting -> "Updating my settings"
        is Command.OpenFile -> "Searching your files…"
        is Command.ClassQuery -> "Checking your timetable"
        is Command.PhotoOf -> "Finding a photo of you two…"
        else -> ""
    }

}

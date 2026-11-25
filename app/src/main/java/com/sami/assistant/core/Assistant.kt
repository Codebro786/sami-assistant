package com.sami.assistant.core

import android.content.Context
import com.sami.assistant.actions.ActionRegistry
import com.sami.assistant.apps.AppRepository
import com.sami.assistant.voice.SpeechInput
import com.sami.assistant.voice.SpeechOutput

enum class OrbState { IDLE, WAKE, LISTENING, THINKING, EXECUTING, RESPONDING, ERROR, CONFIRMATION }

data class Turn(val fromUser: Boolean, val text: String, val status: String? = null, val time: Long = System.currentTimeMillis())

data class AssistantUiState(
    val confirm: com.sami.assistant.actions.ConfirmCard? = null,
    val orb: OrbState = OrbState.IDLE,
    val level: Float = 0f,
    val transcript: String = "",
    val response: String = "",
    val actionLabel: String = "",
    val history: List<Turn> = emptyList(),
    val continuous: Boolean = true,
)

/** One-shot UI events from the ViewModel to the Activity. */
sealed interface UiEvent {
    /** Move Sami out of the way so a screen command acts on the app underneath. */
    data object GetOutOfTheWay : UiEvent
}

/** Manual DI container. Swap for Hilt if the graph grows. */
class AppContainer(context: Context) {
    val prefs = SamiPrefs(context)
    val apps = AppRepository(context)
    val contacts = com.sami.assistant.system.ContactsRepo(context) { prefs.aliases }
    val groq = com.sami.assistant.ai.GroqClient({ prefs.groqKey }, { prefs.groqModel })
    val planner = com.sami.assistant.ai.Planner(groq)
    val actions = ActionRegistry(context, apps, prefs, contacts, planner)
    val speechIn = SpeechInput(context)
    val speechOut = SpeechOutput(context).apply { rate = prefs.ttsRate; pitch = prefs.ttsPitch }
    val assistant = AssistantController(this)
}

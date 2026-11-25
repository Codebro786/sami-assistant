package com.sami.assistant.actions

import com.sami.assistant.commands.Command

enum class Risk { LOW, MEDIUM, HIGH }

/** Real execution outcome. Never report [Success] unless the platform call actually succeeded. */
sealed interface ActionResult {
    val speech: String
    data class Success(override val speech: String) : ActionResult
    data class Failure(override val speech: String, val cause: Throwable? = null) : ActionResult
    /** Platform does not allow it, or it ships in a later milestone. */
    data class Unsupported(override val speech: String) : ActionResult
    /** We opened a system screen; the user must finish the change there. */
    data class HandedOff(override val speech: String) : ActionResult
    /**
     * Sensitive action: show [card] and run [proceed] only after the user confirms
     * (and passes biometrics when [ConfirmCard.requireBiometric]). [proceed] gets the card's text input, if any.
     */
    data class NeedsConfirmation(val card: ConfirmCard, val proceed: suspend (input: String?) -> ActionResult) : ActionResult {
        override val speech get() = card.spoken
    }
}

data class ConfirmCard(
    val title: String,
    val lines: List<String>,
    val spoken: String,
    val confirmLabel: String = "Confirm",
    val requireBiometric: Boolean = false,
    /** Say "yes"/"haan" to confirm (not allowed for payments). */
    val voiceConfirm: Boolean = true,
    val inputHint: String? = null,
    val inputValidator: ((String) -> String?)? = null,
    /** Pre-filled input, e.g. a UPI ID built from the contact's mobile number. */
    val inputPrefill: String? = null,
    /** One-tap suffixes that replace the part after '@' (UPI handles). */
    val inputChips: List<String> = emptyList(),
    /** Payment amount the user can edit on the card; returned as the second line of the input. */
    val editableAmount: Double? = null,
)

interface Action<C : Command> {
    val id: String
    val description: String
    val requiredPermissions: List<String> get() = emptyList()
    val risk: Risk
    /** Returns an error message if the command is invalid, else null. */
    fun validate(command: C): String? = null
    suspend fun execute(command: C): ActionResult
}

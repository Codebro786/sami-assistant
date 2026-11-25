package com.sami.assistant.ui

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sami.assistant.actions.ConfirmCard
import com.sami.assistant.core.AssistantController

/** Confirms a card, running the system fingerprint / face / PIN prompt first when the card requires it. */
fun FragmentActivity.confirmCard(assistant: AssistantController, card: ConfirmCard, input: String?) {
    if (!card.requireBiometric) { assistant.answerConfirm(true, input); return }
    val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = assistant.answerConfirm(true, input)
        override fun onAuthenticationError(code: Int, msg: CharSequence) {
            // User cancelled or no lock set up: keep the card open so they can retry or cancel.
        }
    })
    prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
        .setTitle(card.title).setSubtitle("Confirm it's you").setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build())
}

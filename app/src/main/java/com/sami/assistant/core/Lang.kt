package com.sami.assistant.core

/**
 * "Match me": Sami replies in Hinglish when the user spoke Hinglish/Hindi, else English.
 * Set per turn by the controller from the user's words.
 */
object Lang {
    @Volatile var hinglish = false
    private val markers = Regex("""(^|\s)(ko|karo|kar|kardo|hai|hain|kya|mujhe|mera|meri|bhejo|chalao|kholo|ka|ki|ke|nahi|haan|batao|bata|laga|lagao|baje|kal|aaj|abhi|thoda|zara|yaar|bhai|kitna|kitni|kaisa|kaise|kab|kahan|kyun|dikhao|sunao|bajao|band|chalu)(\s|$)|[\u0900-\u097F]""")
    fun detect(text: String): Boolean = markers.containsMatchIn(text.lowercase())
    /** Applies the user's reply-language preference on top of detection. */
    fun decide(text: String, pref: String) { hinglish = when (pref) { "english" -> false; "hinglish" -> true; else -> detect(text) } }
    fun t(en: String, hi: String) = if (hinglish) hi else en
}

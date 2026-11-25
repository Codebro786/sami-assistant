package com.sami.assistant.apps

data class AppEntry(val label: String, val packageName: String)

/**
 * Pure fuzzy matcher from a spoken app name to an installed app. Kept free of Android
 * types so it is unit-testable. Returns null when nothing is a confident match —
 * Sami must never launch a "close enough" wrong app silently.
 */
object AppMatcher {

    /** Common spoken aliases → canonical label fragments or package names. */
    private val aliases = mapOf(
        "whatsapp" to listOf("whatsapp", "com.whatsapp"),
        "whats app" to listOf("whatsapp", "com.whatsapp"),
        "insta" to listOf("instagram"),
        "yt" to listOf("youtube"),
        "you tube" to listOf("youtube"),
        "yt music" to listOf("youtube music"),
        "gpay" to listOf("google pay", "gpay", "com.google.android.apps.nbu.paisa.user"),
        "g pay" to listOf("google pay", "gpay", "com.google.android.apps.nbu.paisa.user"),
        "phonepe" to listOf("phonepe", "com.phonepe.app"),
        "phone pe" to listOf("phonepe", "com.phonepe.app"),
        "paytm" to listOf("paytm", "net.one97.paytm"),
        "camera" to listOf("camera"),
        "gallery" to listOf("gallery", "photos"),
        "photos" to listOf("photos", "gallery"),
        "maps" to listOf("maps"),
        "google maps" to listOf("maps"),
        "chrome" to listOf("chrome"),
        "browser" to listOf("chrome", "browser", "internet"),
        "play store" to listOf("play store", "com.android.vending"),
        "calculator" to listOf("calculator"),
        "clock" to listOf("clock"),
        "dialer" to listOf("phone", "dialer"),
        "messages" to listOf("messages", "messaging"),
        "spotify" to listOf("spotify"),
        "files" to listOf("files", "file manager", "my files"),
    )

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N} ]"""), "").replace(Regex("""\s+"""), " ").trim()

    fun match(spoken: String, apps: List<AppEntry>): AppEntry? {
        val q = norm(spoken)
        if (q.isEmpty() || apps.isEmpty()) return null

        val candidates = (aliases[q] ?: emptyList()) + q
        for (c in candidates) {
            apps.firstOrNull { it.packageName == c }?.let { return it }
            apps.firstOrNull { norm(it.label) == c }?.let { return it }
        }
        for (c in candidates) {
            apps.filter { norm(it.label).startsWith(c) }.minByOrNull { it.label.length }?.let { return it }
        }
        val qNoSpace = q.replace(" ", "")
        apps.firstOrNull { norm(it.label).replace(" ", "") == qNoSpace }?.let { return it }
        for (c in candidates) {
            apps.filter { norm(it.label).split(" ").contains(c) }.minByOrNull { it.label.length }?.let { return it }
        }
        // Last resort: small edit distance on whole label (handles ASR misspellings like "spotfy").
        val best = apps.map { it to distance(norm(it.label), q) }.minByOrNull { it.second } ?: return null
        val tolerance = if (q.length <= 4) 1 else 2
        return if (best.second <= tolerance) best.first else null
    }

    internal fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}

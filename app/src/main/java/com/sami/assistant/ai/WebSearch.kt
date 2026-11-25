package com.sami.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Sami's own web research: DuckDuckGo results (incl. Reddit threads via site: search),
 * then the top pages are fetched and stripped to text for the AI to read. No API key needed.
 */
object WebSearch {
    data class Result(val title: String, val url: String, val snippet: String) {
        val site get() = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault(url)
    }

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"

    private fun get(url: String, timeout: Int = 7000): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout; instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA); setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
        }
        try {
            android.util.Log.i("SamiWeb", "GET ${url.take(70)} -> ${c.responseCode}")
            if (c.responseCode !in 200..299) return ""
            // Cap download size: we only need the first part of a page.
            return c.inputStream.bufferedReader().use { r ->
                val sb = StringBuilder(); val buf = CharArray(16_384)
                while (sb.length < 400_000) { val n = r.read(buf); if (n <= 0) break; sb.appendRange(buf, 0, n) }
                sb.toString()
            }
        } finally { c.disconnect() }
    }

    private fun clean(html: String) = android.text.Html.fromHtml(html, 0).toString().replace(Regex("""\s+"""), " ").trim()

    suspend fun search(query: String, max: Int = 8): List<Result> = withContext(Dispatchers.IO) {
        val html = runCatching { get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")) }.getOrDefault("")
        android.util.Log.i("SamiWeb", "ddg len=${html.length} results=${html.split("result__body").size - 1} head=${html.take(160).replace(Regex("""\s+"""), " ")}")
        val blocks = html.split("result__body").drop(1)
        blocks.mapNotNull { b ->
            val href = Regex("""class="result__a" href="([^"]+)"""").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
            val url = Regex("""uddg=([^&]+)""").find(href)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: href
            if (url.contains("duckduckgo.com/y.js")) return@mapNotNull null // ads
            val title = Regex("""class="result__a"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let(::clean) ?: url
            val snip = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let(::clean).orEmpty()
            Result(title, url, snip)
        }.distinctBy { it.url }.take(max)
    }

    /** Main readable text of a page (scripts/styles/nav removed), truncated. */
    private fun pageText(url: String, chars: Int): String {
        val html = runCatching { get(url, 6000) }.getOrDefault("")
        if (html.isEmpty()) return ""
        val body = html.replace(Regex("""<(script|style|noscript|svg|nav|header|footer|aside|form)[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), " ")
        val main = Regex("""<(article|main)[\s\S]*?</\1>""", RegexOption.IGNORE_CASE).find(body)?.value ?: body
        return clean(main.replace(Regex("""<[^>]+>"""), " ")).take(chars)
    }

    /** Gathers evidence for the AI: general results + Reddit threads + text of the top pages. */
    suspend fun gather(query: String, deep: Boolean): Pair<String, List<String>> = coroutineScope {
        val general = async { search(query, 8) }
        val reddit = if (deep) async { search("$query site:reddit.com", 5) } else null
        val g = general.await(); val r = reddit?.await().orEmpty()
        val toRead = if (deep) g.filter { !it.url.contains("reddit.com") && !it.url.contains("youtube.com") }.take(4) else emptyList()
        val pages = toRead.map { res -> async(Dispatchers.IO) { res to (withTimeoutOrNull(8000) { pageText(res.url, 2500) } ?: "") } }.awaitAll()
        val sb = StringBuilder()
        g.forEachIndexed { i, it -> sb.append("[${i + 1}] ${it.site} — ${it.title}: ${it.snippet}\n") }
        if (r.isNotEmpty()) { sb.append("\nReddit threads:\n"); r.forEach { sb.append("- ${it.title}: ${it.snippet}\n") } }
        pages.filter { it.second.length > 200 }.forEach { (res, text) -> sb.append("\n=== ${res.site}: ${res.title}\n$text\n") }
        val sites = (g.map { it.site } + if (r.isNotEmpty()) listOf("reddit.com") else emptyList()).distinct()
        sb.toString() to sites
    }
}

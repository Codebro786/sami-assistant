package com.sami.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal OpenAI-compatible client for Groq. The key never leaves the phone except to api.groq.com. */
class GroqClient(private val keyProvider: () -> String?, private val modelProvider: () -> String) {

    class AiException(message: String, val offline: Boolean = false) : Exception(message)

    data class Msg(val role: String, val content: String)

    suspend fun chat(messages: List<Msg>, json: Boolean, maxTokens: Int = 600, model: String? = null): String = withContext(Dispatchers.IO) {
        val key = keyProvider() ?: throw AiException("No Groq key. Add one in Sami › Settings › AI brain.")
        val body = JSONObject()
            .put("model", model ?: modelProvider())
            .put("temperature", 0.2)
            .put("max_completion_tokens", maxTokens)
            .put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        // gpt-oss models "think" before answering; low effort = faster replies and no truncated answers.
        if ((model ?: modelProvider()).contains("gpt-oss")) body.put("reasoning_effort", "low")

        val conn = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8000; readTimeout = 20000; doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code == 401) throw AiException("Groq rejected the key. Check it in Settings.")
            if (code == 429) throw AiException("Groq's free limit is used up for now. Try again in a bit.")
            if (code !in 200..299) throw AiException("Groq error $code: ${runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(120))}")
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        } catch (e: java.io.IOException) {
            throw AiException("I need internet for that.", offline = true)
        } finally { conn.disconnect() }
    }

    /** Lists active model ids for the Settings screen. */
    suspend fun models(): List<String> = withContext(Dispatchers.IO) {
        val key = keyProvider() ?: return@withContext emptyList()
        val conn = (URL("https://api.groq.com/openai/v1/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000; setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("data")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.sorted()
        } catch (_: Exception) { emptyList() } finally { conn.disconnect() }
    }
}

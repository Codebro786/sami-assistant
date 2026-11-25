package com.sami.assistant.system

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Read-only Gmail access with the user's own Google sign-in (scope gmail.readonly).
 * Tokens are managed by Google Play services; Sami never sees the password and stores no token.
 */
class GmailClient(private val context: Context) {
    data class Mail(val from: String, val subject: String, val snippet: String, val date: String, val unread: Boolean)

    sealed interface Auth {
        data class Token(val value: String) : Auth
        /** User must approve once — launch this from an Activity (Settings › Connect Gmail). */
        data class NeedsConsent(val intent: PendingIntent) : Auth
        data class Error(val message: String) : Auth
    }

    private val scope = Scope("https://www.googleapis.com/auth/gmail.readonly")

    suspend fun authorize(): Auth = try {
        val r = Identity.getAuthorizationClient(context)
            .authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(scope)).build()).await()
        when {
            r.hasResolution() -> Auth.NeedsConsent(r.pendingIntent!!)
            r.accessToken != null -> Auth.Token(r.accessToken!!)
            else -> Auth.Error("Google didn't return access.")
        }
    } catch (e: Exception) {
        Auth.Error(e.message ?: "Google sign-in failed")
    }

    suspend fun search(token: String, query: String, max: Int = 15): List<Mail> = withContext(Dispatchers.IO) {
        val list = get(token, "messages?maxResults=$max&q=" + URLEncoder.encode(query, "UTF-8"))
        val arr = list.optJSONArray("messages") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val id = arr.getJSONObject(i).getString("id")
            runCatching {
                val m = get(token, "messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date")
                val headers = m.getJSONObject("payload").getJSONArray("headers")
                fun h(n: String) = (0 until headers.length()).map { headers.getJSONObject(it) }.firstOrNull { it.getString("name").equals(n, true) }?.getString("value").orEmpty()
                val labels = m.optJSONArray("labelIds")?.let { l -> (0 until l.length()).map { l.getString(it) } } ?: emptyList()
                Mail(h("From").substringBefore("<").trim().trim('"').ifEmpty { h("From") }, h("Subject"),
                    android.text.Html.fromHtml(m.optString("snippet"), 0).toString(), h("Date"), "UNREAD" in labels)
            }.getOrNull()
        }
    }

    private fun get(token: String, path: String): JSONObject {
        val c = (URL("https://gmail.googleapis.com/gmail/v1/users/me/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 15000; setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (c.responseCode == 401 || c.responseCode == 403) throw IllegalStateException("Gmail access was refused. Reconnect Gmail in Settings.")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }
}

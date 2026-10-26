package com.frianzo.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import androidx.core.app.RemoteInput
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Handles inline replies typed in a notification (no app open needed).
 *
 * Sends the reply to the matching REST endpoint with the WebView session
 * cookie, then dismisses the notification on success. On failure the
 * notification is left in place so the user can retry the reply or tap to
 * open the app — a failed send never silently eats the reply.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        // Must match MainActivity's URLs (kept local so MainActivity stays untouched).
        private const val SITE_URL = "https://frianzo.online"
        private const val API_URL = "https://api.frianzo.online"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val text = results.getCharSequence(FrianzoMessagingService.KEY_REPLY_TEXT)
            ?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val notificationId = intent.getIntExtra(
            FrianzoMessagingService.EXTRA_NOTIFICATION_ID, -1
        )
        val kind = intent.getStringExtra(FrianzoMessagingService.EXTRA_REPLY_TYPE) ?: return
        val conversationId = intent.getStringExtra(FrianzoMessagingService.EXTRA_CONVERSATION_ID)
        val postId = intent.getStringExtra(FrianzoMessagingService.EXTRA_POST_ID)
        val reelId = intent.getStringExtra(FrianzoMessagingService.EXTRA_REEL_ID)
        val commentId = intent.getStringExtra(FrianzoMessagingService.EXTRA_COMMENT_ID)

        // Network on the main thread is forbidden: do the send on a worker
        // thread and hold the receiver alive with goAsync() until it finishes.
        val pending = goAsync()
        Thread {
            try {
                val ok = sendReply(kind, conversationId, postId, reelId, commentId, text)
                if (ok && notificationId != -1) {
                    val manager =
                        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(notificationId)
                }
            } catch (_: Exception) {
                // Leave the notification in place on failure.
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun getCookieValue(name: String): String? {
        val cookieManager = CookieManager.getInstance()
        val fromApi = cookieManager.getCookie(API_URL)
        val fromSite = cookieManager.getCookie(SITE_URL)
        val all = listOfNotNull(fromApi, fromSite).joinToString(";")
        if (all.isBlank()) return null
        return all.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Returns true only on a 2xx response. Comment replies over the server's
     * 500-character limit are rejected by the server (notification stays, so
     * the user can tap through and send from the app instead).
     */
    private fun sendReply(
        kind: String,
        conversationId: String?,
        postId: String?,
        reelId: String?,
        commentId: String?,
        text: String
    ): Boolean {
        val (path, body) = when (kind) {
            "message" -> {
                val id = conversationId ?: return false
                "/api/messages/conversations/$id/messages" to
                    JSONObject().put("content", text)
            }
            "post_comment" -> {
                val id = postId ?: return false
                "/api/comments/$id" to JSONObject().apply {
                    put("content", text)
                    if (!commentId.isNullOrEmpty()) put("parentCommentId", commentId)
                }
            }
            "reel_comment" -> {
                val id = reelId ?: return false
                "/api/reels/$id/comments" to JSONObject().apply {
                    put("content", text)
                    if (!commentId.isNullOrEmpty()) put("parentCommentId", commentId)
                }
            }
            else -> return false
        }

        val token = getCookieValue("vynzo_auth_token") ?: return false
        val token2 = getCookieValue("vynzo_token")
        val cookies = buildString {
            append("vynzo_auth_token=").append(token)
            if (!token2.isNullOrEmpty()) append("; vynzo_token=").append(token2)
        }

        val connection = URL(API_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Origin", SITE_URL)
            connection.setRequestProperty("Cookie", cookies)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            // Drain the body so the connection can be reused/closed cleanly.
            try {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
            }
            return code in 200..299
        } finally {
            connection.disconnect()
        }
    }
}

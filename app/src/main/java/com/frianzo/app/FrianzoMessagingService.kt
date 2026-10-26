package com.frianzo.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL

/**
 * Frianzo push notification service.
 *
 * The server sends DATA-ONLY FCM messages (no notification payload), so this
 * service always builds the notification itself — even when the app is in
 * background or killed. That is what makes rich notifications possible:
 * the sender's photo as the large icon, and an inline Reply action for
 * direct messages and comments (the reply is sent without opening the app).
 *
 * Also sets the app icon badge count (WhatsApp-style) via
 * NotificationCompat.setNumber(). The launcher shows the badge on the app
 * icon based on this number.
 */
class FrianzoMessagingService : FirebaseMessagingService() {

    companion object {
        const val SITE_URL = "https://frianzo.online"
        const val REPLY_ACTION = "com.frianzo.app.NOTIFICATION_REPLY"
        const val KEY_REPLY_TEXT = "reply_text"
        const val EXTRA_REPLY_TYPE = "reply_type"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_POST_ID = "post_id"
        const val EXTRA_REEL_ID = "reel_id"
        const val EXTRA_COMMENT_ID = "comment_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        // Types that get an inline Reply action. 'message' is the DM push
        // (sent directly by the chat path, not via notifications). Post
        // comment types reply on the post; reel comment types on the reel.
        // Likes, follows and system notifications get no Reply action.
        private val REPLY_COMMENT_TYPES = setOf("post_comment", "comment_reply", "comment_mention")
        private val REPLY_REEL_COMMENT_TYPES = setOf("reel_comment", "reel_reply")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val badge = data["badge"]?.toIntOrNull() ?: 0
        val url = data["url"] ?: "/notifications"

        // Get title/body from notification payload or data
        val title = message.notification?.title ?: data["title"] ?: "Frianzo"
        val body = message.notification?.body ?: data["body"] ?: ""

        // Same-tag notifications replace each other (this preserves the old
        // FCM tag behaviour now that messages are data-only). Untagged
        // notifications stay unique per arrival.
        val tag = data["tag"]
        val notificationId = tag?.hashCode() ?: System.currentTimeMillis().toInt()

        val avatar = data["avatarUrl"]?.let { downloadBitmap(it) }

        showNotification(
            title, body, badge, url, notificationId, avatar,
            data["type"], data["conversationId"], data["postId"],
            data["reelId"], data["commentId"]
        )
    }

    private fun showNotification(
        title: String,
        body: String,
        badge: Int,
        url: String,
        notificationId: Int,
        avatar: Bitmap?,
        type: String?,
        conversationId: String?,
        postId: String?,
        reelId: String?,
        commentId: String?
    ) {
        val channelId = "frianzo_default"
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Frianzo",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to open the app at the given URL
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("fcm_url", url)
        }
        // Unique request code per notification: PendingIntent equality ignores
        // extras, so a fixed request code (0) + FLAG_UPDATE_CURRENT made every
        // notification share one PendingIntent — tapping an older notification
        // opened the newest notification's URL. The same unique value is used
        // as the notification ID below so each notification keeps its own tap target.
        // (Same-tag replacements intentionally reuse one ID, so the newer
        // notification updates the older one's tap target.)
        val pendingIntent = PendingIntent.getActivity(
            this, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setNumber(badge) // <-- app icon badge count (launcher shows 99+ style)
            .setShowWhen(true)

        // Sender photo next to the notification (large icon). Absent or
        // failed downloads just fall back to the plain notification.
        if (avatar != null) {
            builder.setLargeIcon(avatar)
        }

        // Inline Reply action (notification drawer text box, no app open).
        val replyKind = when {
            type == "message" && !conversationId.isNullOrEmpty() -> "message"
            type in REPLY_COMMENT_TYPES && !postId.isNullOrEmpty() -> "post_comment"
            type in REPLY_REEL_COMMENT_TYPES && !reelId.isNullOrEmpty() -> "reel_comment"
            else -> null
        }
        if (replyKind != null) {
            val replyIntent = Intent(this, NotificationReplyReceiver::class.java).apply {
                action = REPLY_ACTION
                putExtra(EXTRA_REPLY_TYPE, replyKind)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                if (conversationId != null) putExtra(EXTRA_CONVERSATION_ID, conversationId)
                if (postId != null) putExtra(EXTRA_POST_ID, postId)
                if (reelId != null) putExtra(EXTRA_REEL_ID, reelId)
                if (commentId != null) putExtra(EXTRA_COMMENT_ID, commentId)
            }
            // RemoteInput needs a MUTABLE PendingIntent: the system writes
            // the typed reply into the intent before delivering it.
            val replyPendingIntent = PendingIntent.getBroadcast(
                this, notificationId, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
                .setLabel("Reply")
                .build()
            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, "Reply", replyPendingIntent
            ).addRemoteInput(remoteInput).build()
            builder.addAction(replyAction)
        }

        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Downloads the sender avatar (small image, short timeouts). Returns null
     * on any failure — the notification still shows, just without the photo.
     * Relative paths are resolved against the site (avatar URLs are usually
     * absolute, but this keeps a relative value from breaking the download).
     */
    private fun downloadBitmap(rawUrl: String): Bitmap? {
        return try {
            val fullUrl = if (rawUrl.startsWith("/")) SITE_URL + rawUrl else rawUrl
            val connection = URL(fullUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return null
            }
            val bitmap = BitmapFactory.decodeStream(connection.inputStream)
            connection.disconnect()
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    override fun onNewToken(token: String) {
        // Token refresh: the WebView already registers the token via PushRegistrar.
        // No-op here to avoid duplicate registration.
    }
}

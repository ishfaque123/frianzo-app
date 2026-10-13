package com.frianzo.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Frianzo push notification service.
 *
 * Handles FCM messages and sets the app icon badge count (WhatsApp-style)
 * via NotificationCompat.setNumber(). The launcher shows the badge on the
 * app icon based on this number.
 */
class FrianzoMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val badge = data["badge"]?.toIntOrNull() ?: 0
        val url = data["url"] ?: "/notifications"

        // Get title/body from notification payload or data
        val title = message.notification?.title ?: data["title"] ?: "Frianzo"
        val body = message.notification?.body ?: data["body"] ?: ""

        showNotification(title, body, badge, url)
    }

    private fun showNotification(title: String, body: String, badge: Int, url: String) {
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
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setNumber(badge) // <-- app icon badge count (launcher shows 99+ style)
            .setShowWhen(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onNewToken(token: String) {
        // Token refresh: the WebView already registers the token via PushRegistrar.
        // No-op here to avoid duplicate registration.
    }
}

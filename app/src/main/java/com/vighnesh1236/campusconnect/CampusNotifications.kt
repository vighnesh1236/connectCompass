package com.vighnesh1236.campusconnect

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.R as AndroidR

object CampusNotifications {
    const val CHANNEL_ID = "campusconnect_messages"
    const val CONNECTION_CHANNEL_ID = "campusconnect_connection"
    const val CONNECTION_NOTIFICATION_ID = 2840
    private const val LOGIN_NOTIFICATION_ID = 2841

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "CampusConnect messages", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New messages from other students and account activity"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_CHANNEL_ID, "Message connection", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps CampusConnect ready to receive student messages"
                setShowBadge(false)
            }
        )
    }

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    fun showLoginNotification(context: Context) {
        show(context, LOGIN_NOTIFICATION_ID, "You're connected", "Welcome to your campus community.")
    }

    fun showMessageNotification(
        context: Context, title: String, body: String,
        conversationId: Int?, messageId: Int?
    ) {
        show(
            context, messageId ?: conversationId ?: 0, title, body, conversationId
        )
    }

    fun connectionNotification(context: Context, connected: Boolean): Notification {
        val intent = PendingIntent.getActivity(
            context, CONNECTION_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CONNECTION_CHANNEL_ID)
            .setSmallIcon(AndroidR.drawable.ic_dialog_email)
            .setContentTitle(if (connected) "CampusConnect is connected" else "CampusConnect is reconnecting")
            .setContentText("Background student message alerts are on")
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun show(
        context: Context,
        id: Int,
        title: String,
        body: String,
        conversationId: Int? = null
    ) {
        if (!notificationsAllowed(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            conversationId?.let { putExtra(MainActivity.EXTRA_CONVERSATION_ID, it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(AndroidR.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

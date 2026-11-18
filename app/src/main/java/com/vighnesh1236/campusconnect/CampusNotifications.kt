package com.vighnesh1236.campusconnect

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.R as AndroidR
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vighnesh1236.campusconnect.data.ApiClient
import com.vighnesh1236.campusconnect.data.DeviceTokenRequest
import com.vighnesh1236.campusconnect.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object CampusNotifications {
    const val CHANNEL_ID = "campusconnect_messages"
    private const val TAG = "CampusNotifications"
    private const val NOTIFICATION_ID = 2841

    fun initialize(context: Context) {
        createChannel(context)
        if (BuildConfig.FIREBASE_APP_ID.isBlank() ||
            BuildConfig.FIREBASE_API_KEY.isBlank() ||
            BuildConfig.FIREBASE_PROJECT_ID.isBlank() ||
            BuildConfig.FIREBASE_SENDER_ID.isBlank()
        ) {
            Log.i(TAG, "Push notifications are disabled until Firebase client settings are configured.")
            return
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build()
            )
        }
    }

    fun registerDevice(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty() || SessionStore.token().isNullOrBlank()) return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> uploadDeviceToken(token) }
            .addOnFailureListener { error -> Log.e(TAG, "Could not obtain an FCM registration token.", error) }
    }

    fun showLoginNotification(context: Context) {
        show(context, NOTIFICATION_ID, "You're connected", "Welcome to your campus community.")
    }

    fun showMessageNotification(context: Context, title: String, body: String, conversationId: String?) {
        show(
            context, (conversationId?.hashCode() ?: 0) and Int.MAX_VALUE,
            title, body, conversationId
        )
    }

    private fun show(
        context: Context,
        id: Int,
        title: String,
        body: String,
        conversationId: String? = null
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            conversationId?.toIntOrNull()?.let { putExtra(MainActivity.EXTRA_CONVERSATION_ID, it) }
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

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "CampusConnect messages", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New messages from other students and account activity"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    internal fun uploadDeviceToken(token: String) {
        if (SessionStore.token().isNullOrBlank()) return
        SessionStore.saveDeviceToken(token)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ApiClient.create().registerDeviceToken(DeviceTokenRequest(token))
            } catch (error: Exception) {
                Log.e(TAG, "Could not register the push notification token.", error)
            }
        }
    }
}

class CampusFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        CampusNotifications.uploadDeviceToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"] ?: message.notification?.title ?: "New message"
        val body = message.data["body"] ?: message.notification?.body.orEmpty()
        CampusNotifications.showMessageNotification(
            this, title, body, message.data["conversationId"]
        )
    }
}

package com.vighnesh1236.campusconnect

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import com.vighnesh1236.campusconnect.data.SessionStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class CampusMessagingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempts = 0
    private var connecting = false
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (SessionStore.token().isNullOrBlank() || SessionStore.userRole() != "student" ||
            !CampusNotifications.notificationsAllowed(this)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification(false)
        if (socket == null && !connecting && reconnectRunnable == null) connect()
        return START_STICKY
    }

    private fun startForegroundNotification(connected: Boolean) {
        val notification = CampusNotifications.connectionNotification(this, connected)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                CampusNotifications.CONNECTION_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(CampusNotifications.CONNECTION_NOTIFICATION_ID, notification)
        }
    }

    private fun updateConnectionNotification(connected: Boolean) {
        if (!CampusNotifications.notificationsAllowed(this)) {
            stopSelf()
            return
        }
        NotificationManagerCompat.from(this).notify(
            CampusNotifications.CONNECTION_NOTIFICATION_ID,
            CampusNotifications.connectionNotification(this, connected)
        )
    }

    private fun connect() {
        val token = SessionStore.token()
        if (token.isNullOrBlank() || stopping) {
            stopSelf()
            return
        }
        connecting = true
        val base = BuildConfig.API_BASE_URL.trimEnd('/')
        val scheme = if (base.startsWith("https://")) "wss://" else "ws://"
        val url = base.replaceFirst(Regex("^https?://"), scheme) + "/api/messages/live"
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (stopping) {
                        webSocket.close(1000, "Signed out")
                        return@post
                    }
                    connecting = false
                    socket = webSocket
                    reconnectAttempts = 0
                    webSocket.send(
                        """{"type":"authenticate","token":${com.google.gson.Gson().toJson(token)}}"""
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JsonParser.parseString(text).asJsonObject
                    when (message.get("type")?.asString) {
                        "ready" -> updateConnectionNotification(true)
                        "message" -> CampusNotifications.showMessageNotification(
                                this@CampusMessagingService,
                                message.get("title")?.asString ?: "New message",
                                message.get("body")?.asString.orEmpty(),
                                message.get("conversationId")?.asInt,
                                message.get("messageId")?.asInt
                            )
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Could not read a live message notification.", error)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post {
                    if (code == 4001 || code == 4003) stopSelf() else disconnected(webSocket)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopping) Log.w(TAG, "Live message connection lost.", t)
                handler.post { disconnected(webSocket) }
            }
        })
    }

    private fun disconnected(failedSocket: WebSocket) {
        if (socket === failedSocket) socket = null
        connecting = false
        if (stopping) return
        updateConnectionNotification(false)
        if (reconnectRunnable != null) return
        reconnectAttempts += 1
        val delaySeconds = (1L shl reconnectAttempts.coerceAtMost(6)).coerceAtMost(60)
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            connect()
        }.also { handler.postDelayed(it, TimeUnit.SECONDS.toMillis(delaySeconds)) }
    }

    override fun onDestroy() {
        stopping = true
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
        socket?.close(1000, "Connection stopped")
        socket = null
        client.dispatcher.cancelAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CampusMessagingService"

        fun ensureRunning(context: Context) {
            if (SessionStore.token().isNullOrBlank() || SessionStore.userRole() != "student" ||
                !CampusNotifications.notificationsAllowed(context)
            ) return
            ContextCompat.startForegroundService(
                context, Intent(context, CampusMessagingService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CampusMessagingService::class.java))
        }
    }
}

package ai.noid.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * User-started foreground-service shell.
 *
 * This service deliberately does not open the microphone yet. Audio capture must not
 * be enabled until a real local Arabic KWS runtime and its battery/false-trigger
 * validation are integrated.
 */
class VoiceForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NOID voice session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible status for a user-started NOID voice session"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                START_NOT_STICKY
            }
            ACTION_START -> {
                // Fail closed: microphone sessions remain disabled until a real local
                // Arabic KWS runtime, permission flow, and battery validation are integrated.
                stopSelf(startId)
                START_NOT_STICKY
            }
            else -> {
                stopSelf(startId)
                START_NOT_STICKY
            }
        }
    }

    internal fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stopIntent = Intent(this, VoiceForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_PENDING_INTENT_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            android.R.drawable.ic_media_pause,
            "إيقاف",
            stopPendingIntent
        ).build()
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NOID — جلسة الصوت")
            .setContentText("الخدمة ظاهرة؛ التقاط الصوت غير مفعّل حالياً.")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "ai.noid.app.action.START_VOICE"
        const val ACTION_STOP = "ai.noid.app.action.STOP_VOICE"
        const val CHANNEL_ID = "noid_voice_session"
        const val NOTIFICATION_ID = 4107
        private const val STOP_PENDING_INTENT_REQUEST_CODE = 4108
    }
}

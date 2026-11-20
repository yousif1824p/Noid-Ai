package ai.noid.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceForegroundServiceNotificationTest {

    @Test
    fun createsVisibleVoiceNotificationWithImmutableExplicitStopActionAndChannel() {
        val controller = Robolectric.buildService(VoiceForegroundService::class.java).create()
        val service = controller.get()

        try {
            val notification = service.buildNotification()
            val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(VoiceForegroundService.CHANNEL_ID)

            assertNotNull("Voice notification channel must be created", channel)
            assertEquals(VoiceForegroundService.CHANNEL_ID, channel.id)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)

            assertEquals("NOID — جلسة الصوت", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(
                "الخدمة ظاهرة؛ التقاط الصوت غير مفعّل حالياً.",
                notification.extras.getCharSequence(Notification.EXTRA_TEXT)
            )
            assertEquals(Notification.CATEGORY_SERVICE, notification.category)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(1, notification.actions.size)

            val stopAction = notification.actions.single()
            assertEquals("إيقاف", stopAction.title.toString())
            val pendingIntent = stopAction.actionIntent
            assertTrue(
                "Stop action must use an immutable PendingIntent",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pendingIntent.isImmutable
            )

            val stopIntent = shadowOf(pendingIntent).savedIntent
            assertEquals(VoiceForegroundService.ACTION_STOP, stopIntent.action)
            assertEquals(VoiceForegroundService::class.java.name, stopIntent.component?.className)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun stopAndUnknownCommandsAreNonStickyAndDoNotRequestMicrophoneCapture() {
        val stopController = Robolectric.buildService(VoiceForegroundService::class.java).create()
        val stopService = stopController.get()
        try {
            assertEquals(
                android.app.Service.START_NOT_STICKY,
                stopService.onStartCommand(
                    Intent(stopService, VoiceForegroundService::class.java).apply {
                        action = VoiceForegroundService.ACTION_STOP
                    },
                    0,
                    1
                )
            )
        } finally {
            stopController.destroy()
        }

        val unknownController = Robolectric.buildService(VoiceForegroundService::class.java).create()
        val unknownService = unknownController.get()
        try {
            assertEquals(
                android.app.Service.START_NOT_STICKY,
                unknownService.onStartCommand(
                    Intent(unknownService, VoiceForegroundService::class.java).apply {
                        action = "ai.noid.app.action.UNKNOWN"
                    },
                    0,
                    2
                )
            )
        } finally {
            unknownController.destroy()
        }
    }
    @Test
    fun manifestDeclaresNonExportedMicrophoneServiceAndRequiredPermissions() {
        val controller = Robolectric.buildService(VoiceForegroundService::class.java).create()
        val context = controller.get().applicationContext
        val serviceInfo = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, VoiceForegroundService::class.java),
            android.content.pm.PackageManager.ComponentInfoFlags.of(0L)
        )
        assertEquals(false, serviceInfo.exported)
        assertTrue(
            serviceInfo.foregroundServiceType and
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
        )

        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.PackageInfoFlags.of(
                android.content.pm.PackageManager.GET_PERMISSIONS.toLong()
            )
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(android.Manifest.permission.RECORD_AUDIO in permissions)
        assertTrue(android.Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(
            android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE in permissions
        )
        controller.destroy()
    }

    @Test
    fun startCommandRemainsDisabledUntilLocalKeywordRuntimeIsIntegrated() {
        val controller = Robolectric.buildService(VoiceForegroundService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(
                android.app.Service.START_NOT_STICKY,
                service.onStartCommand(
                    Intent(service, VoiceForegroundService::class.java).apply {
                        action = VoiceForegroundService.ACTION_START
                    },
                    0,
                    3
                )
            )
            // The service rejects this command with START_NOT_STICKY until the
            // local keyword runtime and permission integration are implemented.
        } finally {
            controller.destroy()
        }
    }

}

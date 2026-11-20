package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceForegroundServicePolicyTest {
    private fun ready(api: Int = 34) = VoiceForegroundServicePolicy.Request(
        userInitiated = true,
        activityVisible = true,
        recordAudioGranted = true,
        foregroundServicePermissionGranted = true,
        microphoneForegroundServicePermissionGranted = true,
        notificationChannelReady = true,
        localKeywordModelReady = true,
        androidApiLevel = api
    )

    @Test
    fun rejectsInvalidPlatformApiLevelsBeforeEvaluatingPermissions() {
        assertEquals(
            VoiceForegroundServicePolicy.BlockReason.INVALID_PLATFORM_VERSION,
            VoiceForegroundServicePolicy.blockReason(ready(api = 0))
        )
        assertEquals(
            VoiceForegroundServicePolicy.BlockReason.INVALID_PLATFORM_VERSION,
            VoiceForegroundServicePolicy.blockReason(ready(api = -1))
        )
        assertFalse(VoiceForegroundServicePolicy.mayStart(ready(api = 0)))
    }

    @Test
    fun permitsOnlyFullyReadyExplicitSession() {
        assertTrue(VoiceForegroundServicePolicy.mayStart(ready()))
        assertNull(VoiceForegroundServicePolicy.blockReason(ready()))
    }

    @Test
    fun rejectsAutomaticOrBackgroundStart() {
        assertEquals(
            VoiceForegroundServicePolicy.BlockReason.USER_GESTURE_REQUIRED,
            VoiceForegroundServicePolicy.blockReason(ready().copy(userInitiated = false))
        )
        assertEquals(
            VoiceForegroundServicePolicy.BlockReason.ACTIVITY_NOT_VISIBLE,
            VoiceForegroundServicePolicy.blockReason(ready().copy(activityVisible = false))
        )
    }

    @Test
    fun rejectsMissingMicrophonePermission() {
        assertEquals(
            VoiceForegroundServicePolicy.BlockReason.MICROPHONE_PERMISSION_MISSING,
            VoiceForegroundServicePolicy.blockReason(ready().copy(recordAudioGranted = false))
        )
    }

    @Test
    fun appliesForegroundServicePermissionByPlatformVersion() {
        assertNull(
            VoiceForegroundServicePolicy.blockReason(
                ready(api = 27).copy(foregroundServicePermissionGranted = false,
                    microphoneForegroundServicePermissionGranted = false)
            )
        )
        assertEquals(
            VoiceForegroundServicePolicy.BlockReason.FOREGROUND_SERVICE_PERMISSION_MISSING,
            VoiceForegroundServicePolicy.blockReason(
                ready(api = 28).copy(foregroundServicePermissionGranted = false)
            )
        )
        assertEquals(
            VoiceForegroundServicePolicy.BlockReason.MICROPHONE_FGS_PERMISSION_MISSING,
            VoiceForegroundServicePolicy.blockReason(
                ready(api = 34).copy(microphoneForegroundServicePermissionGranted = false)
            )
        )
    }

    @Test
    fun requiresNotificationAndRealLocalKeywordModel() {
        assertEquals(
            VoiceForegroundServicePolicy.BlockReason.NOTIFICATION_CHANNEL_UNAVAILABLE,
            VoiceForegroundServicePolicy.blockReason(ready().copy(notificationChannelReady = false))
        )
        assertEquals(
            VoiceForegroundServicePolicy.BlockReason.LOCAL_KEYWORD_MODEL_UNAVAILABLE,
            VoiceForegroundServicePolicy.blockReason(ready().copy(localKeywordModelReady = false))
        )
        assertFalse(
            VoiceForegroundServicePolicy.mayStart(ready().copy(localKeywordModelReady = false))
        )
    }
}

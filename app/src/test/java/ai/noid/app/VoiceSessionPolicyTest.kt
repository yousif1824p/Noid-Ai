package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionPolicyTest {
    private fun readyRequest() = VoiceSessionPolicy.Request(true, true, true, true, true)

    @Test fun permitsOnlyFullyAuthorizedVisibleUserStartedSession() {
        assertTrue(VoiceSessionPolicy.mayStart(readyRequest()))
        assertNull(VoiceSessionPolicy.blockReason(readyRequest()))
    }

    @Test fun rejectsAutomaticStartWithoutUserGesture() {
        val request = readyRequest().copy(userInitiated = false)
        assertFalse(VoiceSessionPolicy.mayStart(request))
        assertEquals(VoiceSessionPolicy.BlockReason.USER_GESTURE_REQUIRED, VoiceSessionPolicy.blockReason(request))
    }

    @Test fun rejectsBackgroundStart() {
        assertEquals(VoiceSessionPolicy.BlockReason.APP_NOT_VISIBLE, VoiceSessionPolicy.blockReason(readyRequest().copy(appVisible = false)))
    }

    @Test fun rejectsMissingMicrophonePermission() {
        assertEquals(VoiceSessionPolicy.BlockReason.MICROPHONE_PERMISSION_MISSING, VoiceSessionPolicy.blockReason(readyRequest().copy(microphonePermissionGranted = false)))
    }

    @Test fun rejectsMissingForegroundServiceOrKeywordRuntime() {
        assertEquals(VoiceSessionPolicy.BlockReason.FOREGROUND_SERVICE_UNAVAILABLE, VoiceSessionPolicy.blockReason(readyRequest().copy(foregroundServiceAvailable = false)))
        assertEquals(VoiceSessionPolicy.BlockReason.KEYWORD_MODEL_NOT_CONFIGURED, VoiceSessionPolicy.blockReason(readyRequest().copy(keywordModelConfigured = false)))
    }
}

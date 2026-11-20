package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceRecognitionTextPolicyTest {
    @Test fun trimsRecognizedText() {
        assertEquals("مرحبا NOID", VoiceRecognitionTextPolicy.accept("  مرحبا NOID  "))
    }

    @Test fun rejectsMissingOrBlankText() {
        assertNull(VoiceRecognitionTextPolicy.accept(null))
        assertNull(VoiceRecognitionTextPolicy.accept(""))
        assertNull(VoiceRecognitionTextPolicy.accept("   "))
    }

    @Test fun boundsRecognizedText() {
        val result = VoiceRecognitionTextPolicy.accept("x".repeat(2_100))
        assertEquals(VoiceRecognitionTextPolicy.MAX_RECOGNIZED_CHARACTERS, result?.length)
    }
}

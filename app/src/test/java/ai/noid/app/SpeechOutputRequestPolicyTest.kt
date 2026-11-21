package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechOutputRequestPolicyTest {
    @Test fun trimsTextAndPreservesSyntheticVoiceSettings() {
        val request = SpeechOutputRequest(
            text = "  مرحباً، أنا NOID  ",
            languageTag = "ar",
            speechRate = 1.1f,
            syntheticTexture = 0.3f
        )
        val accepted = SpeechOutputRequestPolicy.accept(request)
        assertEquals("مرحباً، أنا NOID", accepted?.text)
        assertEquals(1.1f, accepted?.speechRate)
        assertEquals(0.3f, accepted?.syntheticTexture)
    }

    @Test fun rejectsDialectSpecificOutputLocales() {
        listOf("ar-IQ", "ar-SA", "ar-EG").forEach { locale ->
            assertNull(SpeechOutputRequestPolicy.accept(SpeechOutputRequest("مرحباً", locale)))
        }
    }

    @Test fun rejectsBlankAndOversizedText() {
        assertNull(SpeechOutputRequestPolicy.accept(SpeechOutputRequest("  ")))
        assertNull(
            SpeechOutputRequestPolicy.accept(
                SpeechOutputRequest("x".repeat(SpeechOutputRequestPolicy.MAX_TEXT_CHARACTERS + 1))
            )
        )
    }

    @Test fun rejectsUnsupportedLanguageAndOutOfRangeSettings() {
        assertNull(SpeechOutputRequestPolicy.accept(SpeechOutputRequest("hello", "en-US")))
        assertNull(SpeechOutputRequestPolicy.accept(SpeechOutputRequest("مرحباً", speechRate = 2f)))
        assertNull(
            SpeechOutputRequestPolicy.accept(
                SpeechOutputRequest("مرحباً", syntheticTexture = Float.NaN)
            )
        )
    }

    @Test fun acceptsBoundarySettings() {
        assertEquals(
            SpeechOutputRequestPolicy.MIN_SPEECH_RATE,
            SpeechOutputRequestPolicy.accept(
                SpeechOutputRequest("مرحباً", speechRate = SpeechOutputRequestPolicy.MIN_SPEECH_RATE)
            )?.speechRate
        )
        assertEquals(
            SpeechOutputRequestPolicy.MAX_SYNTHETIC_TEXTURE,
            SpeechOutputRequestPolicy.accept(
                SpeechOutputRequest("مرحباً", syntheticTexture = SpeechOutputRequestPolicy.MAX_SYNTHETIC_TEXTURE)
            )?.syntheticTexture
        )
    }

    @Test fun allowsNormalArabicLineBreaksAndTabs() {
        val request = SpeechOutputRequest("مرحباً\nبك\tيا NOID")
        assertEquals(request.text, SpeechOutputRequestPolicy.accept(request)?.text)
    }

    @Test fun rejectsNullAndOtherControlCharacters() {
        assertNull(SpeechOutputRequestPolicy.accept(SpeechOutputRequest("مرحباً\u0000بك")))
        assertNull(SpeechOutputRequestPolicy.accept(SpeechOutputRequest("مرحباً\u0007بك")))
        assertNull(SpeechOutputRequestPolicy.accept(SpeechOutputRequest("مرحباً\u007fبك")))
    }
}

package ai.noid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordSpottingContractTest {
    private fun descriptor() = KeywordModelDescriptor(
        modelId = "noid-ar-wake-v1",
        displayName = "NOID Arabic wake phrase",
        languageTag = "ar-IQ",
        wakePhrase = "يا NOID",
        sampleRateHz = 16_000,
        frameLengthSamples = 1_600,
        localOnly = true,
        modelVersion = "1"
    )

    @Test fun acceptsBoundedLocalArabicModelMetadata() {
        assertTrue(descriptor().isValid())
    }

    @Test fun rejectsRemoteOrUnverifiedModelConfiguration() {
        assertFalse(descriptor().copy(localOnly = false).isValid())
        assertFalse(descriptor().copy(modelId = " ").isValid())
        assertFalse(descriptor().copy(modelVersion = "").isValid())
    }

    @Test fun rejectsUnsupportedAudioFrameConfiguration() {
        assertFalse(descriptor().copy(sampleRateHz = 192_000).isValid())
        assertFalse(descriptor().copy(frameLengthSamples = 0).isValid())
    }
}

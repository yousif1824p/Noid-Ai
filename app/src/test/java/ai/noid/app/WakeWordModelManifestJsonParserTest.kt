package ai.noid.app

import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordModelManifestJsonParserTest {
    private fun validJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("modelFileName", "noid-ar-wake.onnx")
        put("modelSha256", "a".repeat(64))
        put("modelBytes", 12_000_000L)
        put("wakePhrase", "يا NOID")
        put("languageTag", "ar-IQ")
        put("sampleRateHz", 16_000)
        put("channels", 1)
        put("audioWindowSamples", 16_000)
        put("fftSize", 512)
        put("windowSamples", 400)
        put("hopSamples", 160)
        put("centerPaddingSamples", 256)
        put("melBins", 40)
        put("frameCount", 101)
        put("tensorBatch", 1)
        put("tensorMelBins", 40)
        put("tensorFrames", 101)
        put("preprocessingOperations", org.json.JSONArray(listOf("highpass_filter", "log_mel", "cmn")))
        put("preprocessingProfileId", "profile-v1")
        put("preprocessingProfileSha256", "b".repeat(64))
        put("modelInputName", "audio_features")
        put("modelOutputName", "wake_logit")
        put("threshold", 0.82)
        put("consecutiveFrames", 2)
        put("refractoryMillis", 700)
        put("licenseId", "Apache-2.0")
        put("exporterVersion", "1.0")
    }

    @Test fun parsesValidManifestAndPreservesArabicPhrase() {
        val parsed = WakeWordModelManifestJsonParser.parse(validJson().toString())
        assertNotNull(parsed)
        assertEquals("يا NOID", parsed?.wakePhrase)
        assertEquals(101, parsed?.frameCount)
    }

    @Test fun parsesBoundedStreamWithoutClosingCallerStream() {
        val input = ByteArrayInputStream(validJson().toString().toByteArray(Charsets.UTF_8))
        assertNotNull(WakeWordModelManifestJsonParser.parse(input))
        assertEquals(0, input.available())
    }

    @Test fun rejectsMalformedJsonAndUnknownOrMissingFields() {
        assertNull(WakeWordModelManifestJsonParser.parse("{not-json"))
        assertNull(WakeWordModelManifestJsonParser.parse(validJson().put("unexpected", true).toString()))
        val missing = validJson().apply { remove("licenseId") }
        assertNull(WakeWordModelManifestJsonParser.parse(missing.toString()))
    }

    @Test fun rejectsWrongJsonTypesAndFractionalIntegerFields() {
        assertNull(WakeWordModelManifestJsonParser.parse(validJson().put("channels", "1").toString()))
        assertNull(WakeWordModelManifestJsonParser.parse(validJson().put("frameCount", 101.5).toString()))
        assertNull(WakeWordModelManifestJsonParser.parse(
            validJson().put("preprocessingOperations", org.json.JSONArray(listOf("highpass_filter", 4, "cmn"))).toString()
        ))
    }

    @Test fun rejectsInvalidPolicyValuesBeforeReturningManifest() {
        assertNull(WakeWordModelManifestJsonParser.parse(validJson().put("wakePhrase", "hey doc").toString()))
        assertNull(WakeWordModelManifestJsonParser.parse(validJson().put("modelBytes", 0).toString()))
    }

    @Test fun rejectsOversizedManifestStringsAndStreams() {
        val oversized = validJson().put("exporterVersion", "x".repeat(
            WakeWordModelManifestJsonParser.MAX_MANIFEST_BYTES
        )).toString()
        assertNull(WakeWordModelManifestJsonParser.parse(oversized))
        val oversizedStream = ByteArrayInputStream(
            ByteArray(WakeWordModelManifestJsonParser.MAX_MANIFEST_BYTES + 1)
        )
        assertNull(WakeWordModelManifestJsonParser.parse(oversizedStream))
    }

    @Test fun rejectsMalformedUtf8() {
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)
        assertNull(WakeWordModelManifestJsonParser.parse(ByteArrayInputStream(malformed)))
    }
}

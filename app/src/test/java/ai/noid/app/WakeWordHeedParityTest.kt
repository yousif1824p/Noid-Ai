package ai.noid.app

import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordHeedParityTest {
    @Test
    fun realHeedProfileMatchesSharedPythonReferenceFeatures() {
        val payload = resource("heed_v1_payload.bin")
        val expectedBytes = resource("heed_v1_features.bin")
        assertTrue("unexpected DSP payload length", payload.size == 42_888)
        assertTrue("unexpected feature fixture length", expectedBytes.size == 40 * 101 * 4)

        val shapes = listOf(
            "highpass_sos" to listOf(4, 7),
            "notch_50hz_sos" to listOf(1, 7),
            "notch_60hz_sos" to listOf(1, 7),
            "hann_window" to listOf(400),
            "mel_filterbank" to listOf(40, 257)
        )
        var offset = 0
        val sections = shapes.map { (name, shape) ->
            val count = shape.fold(1) { acc, dimension -> acc * dimension }
            WakeWordDspArtifactParser.Section(name, offset, count, shape).also {
                offset += count * 4
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val artifact = WakeWordDspArtifactParser.Artifact("heed-reference", digest, sections)
        val configured = requireNotNull(
            WakeWordFeaturePipeline.fromValidatedArtifact(payload, artifact)
        )

        val samples = FloatArray(16_000) { index ->
            val t = index / 16_000.0
            (0.17 * sin(2.0 * PI * 137.0 * t)
                + 0.11 * sin(2.0 * PI * 440.0 * t)
                + 0.07 * sin(2.0 * PI * 997.0 * t)
                + 0.04 * sin(2.0 * PI * 50.0 * t)
                + 0.03 * sin(2.0 * PI * 60.0 * t)).toFloat()
        }
        val actual = configured.extract(samples)
        val expectedBuffer = ByteBuffer.wrap(expectedBytes).order(ByteOrder.LITTLE_ENDIAN)
        var squaredError = 0.0
        var maximumError = 0.0
        for (index in actual.indices) {
            val expected = expectedBuffer.float
            val error = kotlin.math.abs(actual[index] - expected).toDouble()
            squaredError += error * error
            maximumError = maxOf(maximumError, error)
        }
        val rmse = kotlin.math.sqrt(squaredError / actual.size)
        assertTrue("feature RMSE $rmse exceeds 0.005", rmse < 0.005)
        assertTrue("maximum feature error $maximumError exceeds 0.05", maximumError < 0.05)
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Missing generated Heed parity fixture: $name"
        }.use { it.readBytes() }
}

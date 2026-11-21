package ai.noid.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WakeWordStreamingFeaturePipelineTest {
    private fun identity() = WakeWordSosCascade.fromRows(
        listOf(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f))
    )

    private fun hann() = FloatArray(400) { index ->
        (0.5 - 0.5 * kotlin.math.cos(2.0 * PI * index / 400.0)).toFloat()
    }

    private fun mel() = FloatArray(40 * 257).also { matrix ->
        for (row in 0 until 40) matrix[row * 257 + row + 1] = 1f
    }

    private fun rows() = listOf(
        floatArrayOf(0.8f, 0.1f, 0.05f, -0.2f, 0.03f, 0.1f, -0.02f)
    )

    private fun pipeline() = WakeWordStreamingFeaturePipeline(
        WakeWordSosCascade.fromRows(rows()),
        identity(),
        identity(),
        hann(),
        mel()
    )

    @Test
    fun waitsForOneSecondThenEmitsFeaturesForEachNewChunk() {
        val streaming = pipeline()
        val chunk = FloatArray(1_600) { index ->
            (0.1 * sin(2.0 * PI * 440.0 * index / 16_000.0)).toFloat()
        }
        repeat(9) { assertNull(streaming.processChunk(chunk)) }
        val first = streaming.processChunk(chunk)
        assertNotNull(first)
        assertEquals(40 * 101, first!!.size)
        assertNotNull(streaming.processChunk(chunk))
    }

    @Test
    fun chunkedStreamingMatchesOneShotFilterForTheLatestSlidingWindow() {
        val allSamples = FloatArray(17_600) { index ->
            (0.13 * sin(2.0 * PI * 137.0 * index / 16_000.0) +
                0.07 * sin(2.0 * PI * 997.0 * index / 16_000.0)).toFloat()
        }
        val high = WakeWordSosCascade.fromRows(rows()).process(allSamples)
        val expectedFiltered = identity().process(identity().process(high))
        val expectedWindow = expectedFiltered.copyOfRange(1_600, 17_600)
        val expectedFeatures = WakeWordStftMelCmn().extract(expectedWindow, hann(), mel())

        val streaming = pipeline()
        var actual: FloatArray? = null
        for (start in allSamples.indices step 1_600) {
            actual = streaming.processChunk(allSamples.copyOfRange(start, start + 1_600))
        }
        assertNotNull(actual)
        assertArrayEquals(expectedFeatures, actual!!, 0f)
    }

    @Test
    fun invalidChunkDoesNotAdvanceFilterStateOrWarmup() {
        val streaming = pipeline()
        val invalid = FloatArray(1_600).also { it[800] = Float.NaN }
        try {
            streaming.processChunk(invalid)
            throw AssertionError("Expected non-finite chunk rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        val chunk = FloatArray(1_600) { if (it == 0) 0.4f else 0f }
        repeat(9) { assertNull(streaming.processChunk(chunk)) }
        val expected = pipeline()
        repeat(9) { expected.processChunk(chunk) }
        assertArrayEquals(expected.processChunk(chunk)!!, streaming.processChunk(chunk)!!, 0f)
    }

    @Test
    fun resetClearsRollingWindowAndFilterState() {
        val streaming = pipeline()
        val chunk = FloatArray(1_600) { if (it == 0) 0.4f else 0f }
        repeat(10) { streaming.processChunk(chunk) }
        streaming.reset()
        repeat(9) { assertNull(streaming.processChunk(chunk)) }
        assertNotNull(streaming.processChunk(chunk))
    }

    @Test
    fun configuredArtifactCreatesIndependentStreamingSessions() {
        val fixture = WakeWordFeaturePipelineTestFixture.create()
        val configured = WakeWordFeaturePipeline.fromValidatedArtifact(
            fixture.payload, fixture.artifact
        )!!
        val first = configured.newStreamingSession()
        val second = configured.newStreamingSession()
        val chunk = FloatArray(1_600) { if (it == 0) 0.25f else 0f }
        repeat(9) { first.processChunk(chunk) }
        val firstFeatures = first.processChunk(chunk)
        repeat(9) { second.processChunk(chunk) }
        val secondFeatures = second.processChunk(chunk)
        assertArrayEquals(firstFeatures!!, secondFeatures!!, 0f)
    }
}

private object WakeWordFeaturePipelineTestFixture {
    data class Fixture(
        val payload: ByteArray,
        val artifact: WakeWordDspArtifactParser.Artifact
    )

    fun create(): Fixture {
        val shapes = listOf(
            "highpass_sos" to listOf(4, 7),
            "notch_50hz_sos" to listOf(1, 7),
            "notch_60hz_sos" to listOf(1, 7),
            "hann_window" to listOf(400),
            "mel_filterbank" to listOf(40, 257)
        )
        val payload = ByteBuffer.allocate(42_888).order(ByteOrder.LITTLE_ENDIAN)
        val sections = ArrayList<WakeWordDspArtifactParser.Section>()
        var offset = 0
        for ((name, shape) in shapes) {
            val count = shape.fold(1) { acc, value -> acc * value }
            val values = when (name) {
                "highpass_sos" -> FloatArray(count).also { rows ->
                    for (row in 0 until 4) rows[row * 7] = 1f
                }
                "notch_50hz_sos", "notch_60hz_sos" ->
                    floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f)
                "hann_window" -> FloatArray(400) { index ->
                    (0.5 - 0.5 * kotlin.math.cos(2.0 * PI * index / 400.0)).toFloat()
                }
                else -> FloatArray(40 * 257).also { matrix ->
                    for (row in 0 until 40) matrix[row * 257 + row + 1] = 1f
                }
            }
            values.forEach(payload::putFloat)
            sections += WakeWordDspArtifactParser.Section(name, offset, count, shape)
            offset += count * 4
        }
        val bytes = payload.array()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return Fixture(
            bytes,
            WakeWordDspArtifactParser.Artifact("test-profile", digest, sections)
        )
    }
}

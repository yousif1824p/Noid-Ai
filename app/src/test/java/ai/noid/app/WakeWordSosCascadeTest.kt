package ai.noid.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordSosCascadeTest {
    @Test
    fun preservesIdentitySignal() {
        val filter = WakeWordSosCascade.fromRows(listOf(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f)))
        val input = floatArrayOf(-1f, 0f, 0.5f, 2f)
        assertArrayEquals(input, filter.process(input), 0f)
    }

    @Test
    fun appliesDirectFormTwoTransposedCoefficients() {
        val filter = WakeWordSosCascade.fromRows(listOf(floatArrayOf(0.5f, 0.25f, 0f, 0f, 0f, 0f, 0f)))
        assertArrayEquals(floatArrayOf(0.5f, 0.25f, 0f, 0f), filter.process(floatArrayOf(1f, 0f, 0f, 0f)), 1e-7f)
    }

    @Test
    fun scalesInitialStatesByFirstCascadeInputSample() {
        val filter = WakeWordSosCascade.fromRows(listOf(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0.5f, -0.25f)))
        assertArrayEquals(floatArrayOf(3f, -0.5f), filter.process(floatArrayOf(2f, 0f)), 1e-7f)
    }

    @Test
    fun chunkedProcessingMatchesSingleCallAndRetainsState() {
        val rows = listOf(
            floatArrayOf(0.8f, 0.1f, 0.05f, -0.2f, 0.03f, 0.1f, -0.02f),
            floatArrayOf(0.9f, 0.05f, 0.02f, -0.1f, 0.01f, 0.03f, 0.01f)
        )
        val input = FloatArray(257) { index -> kotlin.math.sin(index * 0.071).toFloat() }
        val whole = WakeWordSosCascade.fromRows(rows).process(input)
        val chunkedFilter = WakeWordSosCascade.fromRows(rows)
        val chunked = chunkedFilter.process(input.copyOfRange(0, 37)) +
            chunkedFilter.process(input.copyOfRange(37, 151)) +
            chunkedFilter.process(input.copyOfRange(151, input.size))
        assertArrayEquals(whole, chunked, 1e-6f)
    }

    @Test
    fun resetRestoresFirstSampleInitialization() {
        val filter = WakeWordSosCascade.fromRows(
            listOf(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0.25f, 0f))
        )
        val first = filter.process(floatArrayOf(4f, 0f))
        filter.reset()
        val afterReset = filter.process(floatArrayOf(4f, 0f))
        assertArrayEquals(first, afterReset, 0f)
    }

    @Test
    fun emptyChunkDoesNotInitializeOrAlterState() {
        val filter = WakeWordSosCascade.fromRows(
            listOf(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0.25f, 0f))
        )
        assertEquals(0, filter.process(floatArrayOf()).size)
        assertArrayEquals(floatArrayOf(5f), filter.process(floatArrayOf(4f)), 0f)
    }

    @Test
    fun rejectsNonFiniteInput() {
        val filter = WakeWordSosCascade.fromRows(listOf(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f)))
        try {
            filter.process(floatArrayOf(1f, Float.NaN))
            throw AssertionError("Expected non-finite input rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun overflowDoesNotCommitInitialOrExistingStreamingState() {
        val filter = WakeWordSosCascade.fromRows(
            // Nonzero feedback creates a retained z1 of -2.5 after the first sample.
            listOf(floatArrayOf(2f, 0f, 0f, 0.5f, 0f, 0.5f, 0f))
        )
        assertArrayEquals(floatArrayOf(5f), filter.process(floatArrayOf(2f)), 0f)
        try {
            filter.process(floatArrayOf(Float.MAX_VALUE))
            throw AssertionError("Expected overflow rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        // The failed chunk must leave the prior state (z1 = -2.5) intact.
        assertArrayEquals(floatArrayOf(1.5f), filter.process(floatArrayOf(2f)), 0f)
    }

    @Test
    fun payloadFactoryRejectsWrongSectionAndOutOfBoundsRanges() {
        assertNull(WakeWordSosCascade.fromPayload(ByteArray(28), WakeWordDspArtifactParser.Section(
            "hann_window", 0, 7, listOf(1, 7)
        )))
        assertNull(WakeWordSosCascade.fromPayload(ByteArray(28), WakeWordDspArtifactParser.Section(
            "highpass_sos", 8, 7, listOf(1, 7)
        )))
        assertNull(WakeWordSosCascade.fromPayload(ByteArray(28), WakeWordDspArtifactParser.Section(
            "highpass_sos", 0, 7, listOf(1, 7)
        )))
    }

    @Test
    fun payloadFactoryDecodesLittleEndianV1Rows() {
        val payload = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .apply { listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f).forEach(::putFloat) }.array()
        val filter = WakeWordSosCascade.fromPayload(payload, WakeWordDspArtifactParser.Section(
            "notch_50hz_sos", 0, 7, listOf(1, 7)
        ))
        assertEquals(0.75f, filter!!.process(floatArrayOf(0.75f))[0], 0f)
    }
}

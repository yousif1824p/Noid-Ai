package ai.noid.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WakeWordFeaturePipelineTest {
    private fun identity() = WakeWordSosCascade.fromRows(
        listOf(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f))
    )

    private fun hann() = FloatArray(400) { index ->
        (0.5 - 0.5 * kotlin.math.cos(2.0 * PI * index / 400.0)).toFloat()
    }

    private fun mel() = FloatArray(40 * 257).also { matrix ->
        for (row in 0 until 40) matrix[row * 257 + row + 1] = 1f
    }

    @Test
    fun appliesHighPassThenBothNotchesBeforeFeatureExtraction() {
        val input = FloatArray(16_000) { index ->
            (0.2 * sin(2.0 * PI * 440.0 * index / 16_000.0)).toFloat()
        }
        val hp = identity()
        val n50 = identity()
        val n60 = identity()
        val expected = WakeWordStftMelCmn().extract(
            n60.process(n50.process(hp.process(input))), hann(), mel()
        )
        val actual = WakeWordFeaturePipeline(identity(), identity(), identity())
            .extract(input, hann(), mel())
        assertArrayEquals(expected, actual, 0f)
    }

    @Test
    fun resetRestoresIndependentStreamInitialization() {
        val rows = listOf(floatArrayOf(0.8f, 0.1f, 0.05f, -0.2f, 0.03f, 0.1f, -0.02f))
        fun pipeline() = WakeWordFeaturePipeline(
            WakeWordSosCascade.fromRows(rows),
            identity(),
            identity()
        )
        val samples = FloatArray(16_000) { index ->
            (0.1 * sin(2.0 * PI * 220.0 * index / 16_000.0)).toFloat()
        }
        val actual = pipeline()
        val first = actual.extract(samples, hann(), mel())
        actual.extract(samples, hann(), mel())
        actual.reset()
        val afterReset = actual.extract(samples, hann(), mel())
        assertArrayEquals(first, afterReset, 0f)
    }

    @Test
    fun rejectsNonOneSecondInputBeforeMutatingFilterState() {
        val pipeline = pipelineWithStatefulFilter()
        try {
            pipeline.extract(FloatArray(100), hann(), mel())
            throw AssertionError("Expected invalid window rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        val valid = FloatArray(16_000) { if (it == 0) 0.5f else 0f }
        val expected = pipelineWithStatefulFilter().extract(valid, hann(), mel())
        assertArrayEquals(expected, pipeline.extract(valid, hann(), mel()), 0f)
    }

    @Test
    fun rejectsNonFiniteAudioBeforeMutatingFilterState() {
        val valid = FloatArray(16_000) { if (it == 0) 0.5f else 0f }
        for (invalidValue in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val pipeline = pipelineWithStatefulFilter()
            val invalid = valid.copyOf().also { it[8_000] = invalidValue }
            try {
                pipeline.extract(invalid, hann(), mel())
                throw AssertionError("Expected non-finite audio rejection")
            } catch (_: IllegalArgumentException) {
                // expected
            }
            val expected = pipelineWithStatefulFilter().extract(valid, hann(), mel())
            assertArrayEquals(expected, pipeline.extract(valid, hann(), mel()), 0f)
        }
    }

    @Test
    fun rejectsInvalidHannBeforeMutatingFilterState() {
        val pipeline = pipelineWithStatefulFilter()
        try {
            pipeline.extract(FloatArray(16_000), FloatArray(399), mel())
            throw AssertionError("Expected invalid Hann rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        val valid = FloatArray(16_000) { if (it == 0) 0.5f else 0f }
        val expected = pipelineWithStatefulFilter().extract(valid, hann(), mel())
        assertArrayEquals(expected, pipeline.extract(valid, hann(), mel()), 0f)
    }

    @Test
    fun rejectsInvalidMelBeforeMutatingFilterState() {
        val pipeline = pipelineWithStatefulFilter()
        val invalidMel = mel().also { it[0] = -1f }
        try {
            pipeline.extract(FloatArray(16_000), hann(), invalidMel)
            throw AssertionError("Expected invalid mel filterbank rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        val valid = FloatArray(16_000) { if (it == 0) 0.5f else 0f }
        val expected = pipelineWithStatefulFilter().extract(valid, hann(), mel())
        assertArrayEquals(expected, pipeline.extract(valid, hann(), mel()), 0f)
    }

    @Test
    fun validatedArtifactFactoryBindsAllFiveSectionsToThePipeline() {
        val (payload, artifact, expectedHann, expectedMel) = artifactFixture()
        val configured = WakeWordFeaturePipeline.fromValidatedArtifact(payload, artifact)
        assertNotNull(configured)

        val input = FloatArray(16_000) { index ->
            (0.15 * sin(2.0 * PI * 330.0 * index / 16_000.0)).toFloat()
        }
        val expected = WakeWordStftMelCmn().extract(
            input, expectedHann, expectedMel
        )
        assertArrayEquals(expected, configured!!.extract(input), 0f)
    }

    @Test
    fun validatedArtifactFactoryRejectsPayloadDigestMismatch() {
        val (payload, artifact) = artifactFixture()
        payload[0] = (payload[0].toInt() xor 1).toByte()
        assertNull(WakeWordFeaturePipeline.fromValidatedArtifact(payload, artifact))
    }

    @Test
    fun validatedArtifactFactoryRejectsMissingOrReorderedSections() {
        val (payload, artifact) = artifactFixture()
        val missing = artifact.copy(sections = artifact.sections.dropLast(1))
        assertNull(WakeWordFeaturePipeline.fromValidatedArtifact(payload, missing))
        val reordered = artifact.copy(sections = artifact.sections.reversed())
        assertNull(WakeWordFeaturePipeline.fromValidatedArtifact(payload, reordered))
    }

    @Test
    fun configuredPipelineRetainsFilterStateAndCanReset() {
        val (payload, artifact) = artifactFixture()
        val configured = requireNotNull(
            WakeWordFeaturePipeline.fromValidatedArtifact(payload, artifact)
        )
        val samples = FloatArray(16_000) { if (it == 0) 0.4f else 0f }
        val first = configured.extract(samples)
        configured.extract(samples)
        configured.reset()
        assertArrayEquals(first, configured.extract(samples), 0f)
    }

    private fun artifactFixture(): Fixture {
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
        var expectedHann = FloatArray(0)
        var expectedMel = FloatArray(0)
        for ((name, shape) in shapes) {
            val count = shape.fold(1) { acc, value -> acc * value }
            val values = when (name) {
                "highpass_sos" -> FloatArray(count).also { rows ->
                    for (row in 0 until 4) rows[row * 7] = 1f
                }
                "notch_50hz_sos", "notch_60hz_sos" ->
                    floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f)
                "hann_window" -> hann()
                else -> mel()
            }
            values.forEach(payload::putFloat)
            if (name == "hann_window") expectedHann = values
            if (name == "mel_filterbank") expectedMel = values
            sections += WakeWordDspArtifactParser.Section(name, offset, count, shape)
            offset += count * 4
        }
        val bytes = payload.array()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return Fixture(
            bytes,
            WakeWordDspArtifactParser.Artifact("test-profile", digest, sections),
            expectedHann,
            expectedMel
        )
    }

    private data class Fixture(
        val payload: ByteArray,
        val artifact: WakeWordDspArtifactParser.Artifact,
        val hann: FloatArray = FloatArray(0),
        val mel: FloatArray = FloatArray(0)
    )

    private fun pipelineWithStatefulFilter() = WakeWordFeaturePipeline(
        WakeWordSosCascade.fromRows(
            listOf(floatArrayOf(0.8f, 0.1f, 0.05f, -0.2f, 0.03f, 0.1f, -0.02f))
        ),
        identity(),
        identity()
    )
}

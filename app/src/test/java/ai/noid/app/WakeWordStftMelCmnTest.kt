package ai.noid.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

class WakeWordStftMelCmnTest {
    private val extractor = WakeWordStftMelCmn()

    private fun hann(): FloatArray = FloatArray(400) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / 400.0)).toFloat()
    }

    private fun mel(): FloatArray = FloatArray(40 * 257).also { matrix ->
        // Distinct one-hot bins make row-major mel indexing observable.
        for (row in 0 until 40) matrix[row * 257 + row + 1] = 1f
    }

    @Test
    fun emitsMelMajorTensorWithExactReferenceGeometry() {
        val result = extractor.extract(FloatArray(16_000), hann(), mel())
        assertEquals(40 * 101, result.size)
        assertTrue(result.all(Float::isFinite))
    }

    @Test
    fun silenceBecomesZeroAfterLogFloorAndCmn() {
        val result = extractor.extract(FloatArray(16_000), hann(), mel())
        assertArrayEquals(FloatArray(4_040), result, 1e-6f)
    }

    @Test
    fun constantSignalIsRemovedByPerMelMeanNormalization() {
        val result = extractor.extract(FloatArray(16_000) { 0.25f }, hann(), mel())
        assertTrue(result.all { kotlin.math.abs(it) < 1e-4f })
    }

    @Test
    fun centeredImpulseProducesFiniteNonConstantFeatures() {
        val samples = FloatArray(16_000)
        samples[8_000] = 1f
        val result = extractor.extract(samples, hann(), mel())
        assertTrue(result.all(Float::isFinite))
        assertTrue(result.any { kotlin.math.abs(it) > 1e-3f })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongAudioWindowLength() {
        extractor.extract(FloatArray(15_999), hann(), mel())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongHannGeometry() {
        extractor.extract(FloatArray(16_000), FloatArray(399), mel())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHannWeightsOutsideUnitRange() {
        val window = hann().also { it[200] = 1.01f }
        extractor.extract(FloatArray(16_000), window, mel())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeMelWeights() {
        val matrix = mel()
        matrix[0] = -1f
        extractor.extract(FloatArray(16_000), hann(), matrix)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteAudio() {
        val samples = FloatArray(16_000)
        samples[10] = Float.NaN
        extractor.extract(samples, hann(), mel())
    }
}

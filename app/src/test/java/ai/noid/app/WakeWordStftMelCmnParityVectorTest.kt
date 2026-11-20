package ai.noid.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class WakeWordStftMelCmnParityVectorTest {
    @Test
    fun matchesSharedPythonFeatureGoldenVector() {
        val samples = FloatArray(16_000) { index ->
            val seconds = index / 16_000.0
            ((0.18 * sin(2.0 * PI * 440.0 * seconds) +
                0.07 * cos(2.0 * PI * 997.0 * seconds)) *
                (0.7 + 0.25 * sin(2.0 * PI * 3.0 * seconds))).toFloat()
        }
        val hann = FloatArray(400) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / 400.0)).toFloat()
        }
        val mel = FloatArray(40 * 257).also { matrix ->
            for (row in 0 until 40) {
                val offset = row * 257 + row * 3 + 1
                matrix[offset] = 0.25f
                matrix[offset + 1] = 0.5f
                matrix[offset + 2] = 0.25f
            }
        }
        val expectedLine = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("wakeword-feature-parity-v1.csv")
        ).bufferedReader().useLines { lines ->
            lines.first { it.startsWith("expected,") }
        }
        val expected = expectedLine.substringAfter(',').split(',').map(String::toFloat).toFloatArray()
        val actual = WakeWordStftMelCmn().extract(samples, hann, mel)

        assertEquals(4_040, expected.size)
        // Kotlin's staged Float32 FFT differs slightly from NumPy's Float64 FFT.
        assertArrayEquals(expected, actual, 0.008f)
    }
}

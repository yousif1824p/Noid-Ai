package ai.noid.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Heed-reference log-mel + per-bin CMN extractor.
 *
 * Input is one mono 16 kHz / 1 s window. The caller must apply the validated
 * SOS filter profile before calling this class. Output is mel-major [40, 101].
 */
internal class WakeWordStftMelCmn {
    fun extract(
        samples: FloatArray,
        hannWindow: FloatArray,
        melFilterbank: FloatArray
    ): FloatArray {
        require(samples.size == SAMPLE_COUNT) { "Expected exactly 16000 audio samples" }
        require(samples.all(Float::isFinite)) { "Audio contains non-finite samples" }
        require(hannWindow.size == WINDOW_SIZE &&
            hannWindow.all { it.isFinite() && it in 0f..1f }) {
            "Invalid Hann window"
        }
        require(melFilterbank.size == MEL_BINS * FFT_BINS &&
            melFilterbank.all { it.isFinite() && it >= 0f }) {
            "Invalid dense mel filterbank"
        }

        val padded = reflectPad(samples, FFT_SIZE / 2)
        val output = FloatArray(MEL_BINS * FRAME_COUNT)
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)
        val power = FloatArray(FFT_BINS)

        for (frame in 0 until FRAME_COUNT) {
            real.fill(0f)
            imag.fill(0f)
            val frameStart = frame * HOP_SIZE
            for (i in 0 until WINDOW_SIZE) {
                real[WINDOW_OFFSET + i] =
                    padded[frameStart + WINDOW_OFFSET + i] * hannWindow[i]
            }
            fftInPlace(real, imag)

            for (bin in 0 until FFT_BINS) {
                val re = real[bin]
                val im = imag[bin]
                power[bin] = re * re + im * im
            }

            for (mel in 0 until MEL_BINS) {
                val rowOffset = mel * FFT_BINS
                var energy = 0.0
                for (bin in 0 until FFT_BINS) {
                    energy += melFilterbank[rowOffset + bin].toDouble() *
                        power[bin].toDouble()
                }
                output[mel * FRAME_COUNT + frame] =
                    ln(energy.coerceAtLeast(LOG_FLOOR.toDouble())).toFloat()
            }
        }

        // Cepstral mean normalization: subtract each mel channel's mean over
        // all 101 frames, preserving the upstream [mel, frame] tensor layout.
        for (mel in 0 until MEL_BINS) {
            val offset = mel * FRAME_COUNT
            var mean = 0.0
            for (frame in 0 until FRAME_COUNT) mean += output[offset + frame]
            mean /= FRAME_COUNT
            for (frame in 0 until FRAME_COUNT) {
                output[offset + frame] = (output[offset + frame] - mean).toFloat()
            }
        }
        return output
    }

    private fun reflectPad(input: FloatArray, padding: Int): FloatArray {
        require(input.size > padding) { "Input too short for reflect padding" }
        val result = FloatArray(input.size + 2 * padding)
        for (index in result.indices) {
            val sourceIndex = when {
                index < padding -> padding - index
                index >= padding + input.size ->
                    input.size - 2 - (index - (padding + input.size))
                else -> index - padding
            }
            result[index] = input[sourceIndex]
        }
        return result
    }

    /** In-place radix-2 forward FFT, matching the unnormalised STFT convention. */
    private fun fftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size
        require(n > 0 && n and (n - 1) == 0)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imag[i]; imag[i] = imag[j]; imag[j] = im
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImag = sin(angle)
            val half = length / 2
            var base = 0
            while (base < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until half) {
                    val even = base + k
                    val odd = even + half
                    val oddReal = real[odd].toDouble() * wr -
                        imag[odd].toDouble() * wi
                    val oddImag = real[odd].toDouble() * wi +
                        imag[odd].toDouble() * wr
                    val evenReal = real[even].toDouble()
                    val evenImag = imag[even].toDouble()
                    real[even] = (evenReal + oddReal).toFloat()
                    imag[even] = (evenImag + oddImag).toFloat()
                    real[odd] = (evenReal - oddReal).toFloat()
                    imag[odd] = (evenImag - oddImag).toFloat()
                    val nextWr = wr * stepReal - wi * stepImag
                    wi = wr * stepImag + wi * stepReal
                    wr = nextWr
                }
                base += length
            }
            length = length shl 1
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val SAMPLE_COUNT = 16_000
        const val FFT_SIZE = 512
        const val WINDOW_SIZE = 400
        const val WINDOW_OFFSET = (FFT_SIZE - WINDOW_SIZE) / 2
        const val HOP_SIZE = 160
        const val MEL_BINS = 40
        const val FFT_BINS = FFT_SIZE / 2 + 1
        const val FRAME_COUNT = 101
        const val LOG_FLOOR = 1e-9f
    }
}

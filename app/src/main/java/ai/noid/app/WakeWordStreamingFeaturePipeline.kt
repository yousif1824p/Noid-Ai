package ai.noid.app

/**
 * Correct streaming Heed preprocessing boundary.
 *
 * Each incoming PCM chunk is filtered exactly once, then appended to a rolling
 * one-second filtered-audio window. Features are emitted only after that window
 * is full. Re-filtering overlapping one-second windows would advance the causal
 * SOS state over old samples repeatedly and diverge from Heed's streaming
 * reference implementation.
 */
internal class WakeWordStreamingFeaturePipeline(
    private val highPass: WakeWordSosCascade,
    private val notch50Hz: WakeWordSosCascade,
    private val notch60Hz: WakeWordSosCascade,
    private val hannWindow: FloatArray,
    private val melFilterbank: FloatArray,
    private val extractor: WakeWordStftMelCmn = WakeWordStftMelCmn()
) {
    private val filteredWindow = FloatArray(WakeWordStftMelCmn.SAMPLE_COUNT)
    private var samplesInWindow = 0

    init {
        require(hannWindow.size == WakeWordStftMelCmn.WINDOW_SIZE)
        require(hannWindow.all { it.isFinite() && it in 0f..1f })
        require(melFilterbank.size ==
            WakeWordStftMelCmn.MEL_BINS * WakeWordStftMelCmn.FFT_BINS)
        require(melFilterbank.all { it.isFinite() && it >= 0f })
    }

    /**
     * Ingests one new mono PCM chunk (converted to normalized float samples).
     * Returns the current [1, 40, 101] mel-major feature payload once the
     * rolling one-second window is full; otherwise returns null during warm-up.
     */
    @Synchronized
    fun processChunk(samples: FloatArray): FloatArray? {
        require(samples.isNotEmpty() && samples.size <= MAX_CHUNK_SAMPLES) {
            "Audio chunk length is outside the supported streaming range"
        }
        require(samples.all(Float::isFinite)) {
            "Audio contains non-finite samples"
        }
        require(samples.all { it in NORMALIZED_PCM_RANGE }) {
            "Audio samples must be normalized to the [-1, 1] range"
        }

        // Filter each new sample once. The SOS states survive chunk boundaries.
        val highPassed = highPass.process(samples)
        val without50Hz = notch50Hz.process(highPassed)
        val filtered = notch60Hz.process(without50Hz)

        if (filtered.size >= filteredWindow.size) {
            filtered.copyInto(
                filteredWindow,
                destinationOffset = 0,
                startIndex = filtered.size - filteredWindow.size
            )
            samplesInWindow = filteredWindow.size
        } else {
            val retained = filteredWindow.size - filtered.size
            filteredWindow.copyInto(
                destination = filteredWindow,
                destinationOffset = 0,
                startIndex = filtered.size,
                endIndex = filteredWindow.size
            )
            filtered.copyInto(filteredWindow, destinationOffset = retained)
            samplesInWindow = (samplesInWindow + filtered.size).coerceAtMost(filteredWindow.size)
        }

        if (samplesInWindow < filteredWindow.size) return null
        return extractor.extract(filteredWindow, hannWindow, melFilterbank)
    }

    @Synchronized
    fun reset() {
        highPass.reset()
        notch50Hz.reset()
        notch60Hz.reset()
        filteredWindow.fill(0f)
        samplesInWindow = 0
    }

    companion object {
        const val MAX_CHUNK_SAMPLES = 4_800
        private val NORMALIZED_PCM_RANGE = -1f..1f
    }
}

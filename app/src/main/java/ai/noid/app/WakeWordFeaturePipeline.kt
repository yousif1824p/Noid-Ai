package ai.noid.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * One-window/batch bridge from a validated DSP v1 profile to log-mel features.
 *
 * Use [Configured.newStreamingSession] for live inference. Calling this
 * batch extractor repeatedly with overlapping windows would feed old samples
 * through the retained causal filter state more than once.
 */
internal class WakeWordFeaturePipeline(
    private val highPass: WakeWordSosCascade,
    private val notch50Hz: WakeWordSosCascade,
    private val notch60Hz: WakeWordSosCascade,
    private val extractor: WakeWordStftMelCmn = WakeWordStftMelCmn()
) {
    /** Owns the DSP arrays decoded from one validated payload. */
    internal class Configured internal constructor(
        private val pipeline: WakeWordFeaturePipeline,
        private val hannWindow: FloatArray,
        private val melFilterbank: FloatArray
    ) {
        @Synchronized
        fun extract(samples: FloatArray): FloatArray =
            pipeline.extract(samples, hannWindow, melFilterbank)

        @Synchronized
        fun reset() = pipeline.reset()
    }

    @Synchronized
    fun extract(
        samples: FloatArray,
        hannWindow: FloatArray,
        melFilterbank: FloatArray
    ): FloatArray {
        require(samples.size == WakeWordStftMelCmn.SAMPLE_COUNT) {
            "Expected exactly one second of 16 kHz mono audio"
        }
        // Validate before any stateful SOS stage runs. Otherwise a NaN/Inf
        // sample can poison retained filter state even though extraction fails.
        require(samples.all(Float::isFinite)) {
            "Audio contains non-finite samples"
        }
        // Validate every stateless DSP input before advancing any retained SOS
        // state. A bad window/filterbank must not partially consume this chunk.
        require(hannWindow.size == WakeWordStftMelCmn.WINDOW_SIZE &&
            hannWindow.all { it.isFinite() && it in 0f..1f }) {
            "Invalid Hann window"
        }
        require(melFilterbank.size ==
            WakeWordStftMelCmn.MEL_BINS * WakeWordStftMelCmn.FFT_BINS &&
            melFilterbank.all { it.isFinite() && it >= 0f }) {
            "Invalid dense mel filterbank"
        }
        val highPassed = highPass.process(samples)
        val without50Hz = notch50Hz.process(highPassed)
        val filtered = notch60Hz.process(without50Hz)
        return extractor.extract(filtered, hannWindow, melFilterbank)
    }

    @Synchronized
    fun reset() {
        highPass.reset()
        notch50Hz.reset()
        notch60Hz.reset()
    }

    companion object {
        private val sectionOrder = listOf(
            "highpass_sos", "notch_50hz_sos", "notch_60hz_sos",
            "hann_window", "mel_filterbank"
        )
        private val sectionShapes = mapOf(
            "highpass_sos" to listOf(4, 7),
            "notch_50hz_sos" to listOf(1, 7),
            "notch_60hz_sos" to listOf(1, 7),
            "hann_window" to listOf(400),
            "mel_filterbank" to listOf(40, 257)
        )

        /**
         * Creates a runnable preprocessor only from the exact payload accepted
         * by WakeWordDspArtifactParser. The whole-payload digest is rechecked
         * here so metadata cannot accidentally be paired with another payload.
         */
        fun fromValidatedArtifact(
            payload: ByteArray,
            artifact: WakeWordDspArtifactParser.Artifact
        ): Configured? {
            if (payload.size != 42_888 ||
                !sha256(payload).equals(artifact.payloadSha256, ignoreCase = true) ||
                artifact.sections.map { it.name } != sectionOrder
            ) return null

            var expectedOffset = 0L
            for (section in artifact.sections) {
                val shape = sectionShapes[section.name] ?: return null
                val count = shape.fold(1) { acc, dimension -> acc * dimension }
                if (section.shape != shape || section.elementCount != count ||
                    section.offsetBytes.toLong() != expectedOffset
                ) return null
                expectedOffset += count.toLong() * 4L
            }
            if (expectedOffset != payload.size.toLong()) return null

            val sections = artifact.sections.associateBy { it.name }
            val highPass = WakeWordSosCascade.fromPayload(
                payload, sections.getValue("highpass_sos")
            ) ?: return null
            val notch50 = WakeWordSosCascade.fromPayload(
                payload, sections.getValue("notch_50hz_sos")
            ) ?: return null
            val notch60 = WakeWordSosCascade.fromPayload(
                payload, sections.getValue("notch_60hz_sos")
            ) ?: return null
            val hann = decodeFloatSection(payload, sections.getValue("hann_window"))
                ?: return null
            val mel = decodeFloatSection(payload, sections.getValue("mel_filterbank"))
                ?: return null

            if (hann.any { it < 0f || it > 1f } || mel.any { it < 0f }) return null
            return Configured(
                WakeWordFeaturePipeline(highPass, notch50, notch60),
                hann,
                mel
            )
        }

        private fun decodeFloatSection(
            payload: ByteArray,
            section: WakeWordDspArtifactParser.Section
        ): FloatArray? {
            val end = section.offsetBytes.toLong() + section.elementCount.toLong() * 4L
            if (section.offsetBytes < 0 || end > payload.size.toLong()) return null
            return try {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                buffer.position(section.offsetBytes)
                FloatArray(section.elementCount) {
                    buffer.float.also { value -> if (!value.isFinite()) return null }
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

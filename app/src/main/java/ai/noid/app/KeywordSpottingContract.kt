package ai.noid.app

/**
 * Boundary for a future on-device keyword-spotting runtime.
 *
 * This contract deliberately contains no fake acoustic detector. A concrete
 * implementation must load a verified local model and process microphone
 * frames only after VoiceSessionPolicy has authorized a user-started session.
 * Validate each frame and returned event with KeywordSpottingBoundaryPolicy.
 */
data class KeywordModelDescriptor(
    val modelId: String,
    val displayName: String,
    val languageTag: String,
    val wakePhrase: String,
    val sampleRateHz: Int,
    val frameLengthSamples: Int,
    val localOnly: Boolean,
    val modelVersion: String
) {
    fun isValid(): Boolean =
        modelId.isNotBlank() &&
            displayName.isNotBlank() &&
            languageTag.isNotBlank() &&
            wakePhrase.isNotBlank() &&
            sampleRateHz in 8_000..48_000 &&
            frameLengthSamples in 80..4_800 &&
            localOnly &&
            modelVersion.isNotBlank()
}

sealed interface KeywordSpottingEvent {
    data class Detected(val phrase: String, val confidence: Float) : KeywordSpottingEvent
    data class Unavailable(val reason: String) : KeywordSpottingEvent
}

/**
 * Implementations must not download models, transmit audio, or start capture.
 * Audio ownership and lifecycle remain with the foreground-session controller.
 * Frames must match descriptor.frameLengthSamples exactly.
 */
interface KeywordSpottingRuntime : AutoCloseable {
    val descriptor: KeywordModelDescriptor
    fun processAudioFrame(pcm16: ShortArray): KeywordSpottingEvent?
    override fun close()
}

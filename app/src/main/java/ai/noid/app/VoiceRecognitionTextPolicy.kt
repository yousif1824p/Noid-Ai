package ai.noid.app

/** Bounds and normalizes text returned by the system speech recognizer. */
object VoiceRecognitionTextPolicy {
    const val MAX_RECOGNIZED_CHARACTERS = 2_000

    fun accept(candidate: String?): String? {
        val normalized = candidate?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return normalized.take(MAX_RECOGNIZED_CHARACTERS)
    }
}

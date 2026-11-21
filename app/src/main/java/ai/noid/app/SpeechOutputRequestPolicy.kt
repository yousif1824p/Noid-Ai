package ai.noid.app

/**
 * Validates bounded plain-text speech requests before they reach a synthesis provider.
 * NOID speech output is standardized on generic Arabic (MSA); dialect-specific
 * locale tags are deliberately rejected.
 */
object SpeechOutputRequestPolicy {
    // Kotlin String.length counts UTF-16 code units.
    const val MAX_TEXT_CHARACTERS = 4_000
    const val MIN_SPEECH_RATE = 0.75f
    const val MAX_SPEECH_RATE = 1.25f
    const val MIN_SYNTHETIC_TEXTURE = 0.0f
    const val MAX_SYNTHETIC_TEXTURE = 1.0f

    fun accept(request: SpeechOutputRequest): SpeechOutputRequest? {
        val text = request.text.trim()
        if (text.isEmpty() || text.length > MAX_TEXT_CHARACTERS) return null
        if (text.any { Character.isISOControl(it) && it !in ALLOWED_TEXT_CONTROLS }) {
            return null
        }
        if (request.languageTag !in SUPPORTED_LANGUAGE_TAGS) return null
        if (!request.speechRate.isFinite() ||
            request.speechRate !in MIN_SPEECH_RATE..MAX_SPEECH_RATE
        ) return null
        if (!request.syntheticTexture.isFinite() ||
            request.syntheticTexture !in MIN_SYNTHETIC_TEXTURE..MAX_SYNTHETIC_TEXTURE
        ) return null

        return request.copy(text = text)
    }

    private val ALLOWED_TEXT_CONTROLS = setOf('\n', '\r', '\t')
    private val SUPPORTED_LANGUAGE_TAGS = setOf("ar")
}

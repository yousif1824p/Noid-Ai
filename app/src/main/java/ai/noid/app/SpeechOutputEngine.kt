package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A speech request for NOID's intentionally synthetic, gender-neutral voice.
 *
 * The contract describes output only; it is independent from microphone capture,
 * speech recognition, and wake-word detection.
 */
data class SpeechOutputRequest(
    val text: String,
    val languageTag: String = "ar",
    val speechRate: Float = 1.0f,
    val syntheticTexture: Float = 0.2f
)

sealed interface SpeechOutputStatus {
    data object NotConfigured : SpeechOutputStatus
    data class Ready(val voiceId: String) : SpeechOutputStatus
    data class Unavailable(val reason: String) : SpeechOutputStatus
}

sealed interface SpeechOutputEvent {
    data object Started : SpeechOutputEvent
    data object Completed : SpeechOutputEvent
}

/**
 * Provider-neutral TTS boundary. Implementations must be cancellable, must not
 * upload text unless a separate explicit remote-speech choice is introduced,
 * and must not claim a voice profile is available before runtime verification.
 */
interface SpeechOutputEngine {
    val status: StateFlow<SpeechOutputStatus>

    fun speak(request: SpeechOutputRequest): Flow<SpeechOutputEvent>

    suspend fun stop()

    suspend fun close()
}

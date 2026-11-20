package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A user-selected image reference. The URI is a content:// URI granted by
 * Android's document picker; implementations must open it through
 * ContentResolver and must not bypass Android storage permissions.
 */
data class VisionImageInput(
    val contentUri: String,
    val mimeType: String,
    val byteCount: Long?
)

data class VisionAnalysisRequest(
    val prompt: String,
    val image: VisionImageInput
)

sealed interface VisionEngineStatus {
    data object NotConfigured : VisionEngineStatus
    data class Ready(val modelId: String) : VisionEngineStatus
    data class Unavailable(val reason: String) : VisionEngineStatus
}

sealed interface VisionStreamEvent {
    data class TextDelta(val text: String) : VisionStreamEvent
    data object Completed : VisionStreamEvent
}

/**
 * Optional vision capability, separate from chat text inference.
 * Implementations must enforce the input policy, support cancellation,
 * and never execute instructions found inside an image.
 */
interface VisionEngine {
    val status: StateFlow<VisionEngineStatus>

    fun analyze(request: VisionAnalysisRequest): Flow<VisionStreamEvent>

    suspend fun close()
}

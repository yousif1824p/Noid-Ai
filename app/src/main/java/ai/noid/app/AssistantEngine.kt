package ai.noid.app

import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Describes whether a real inference runtime is available.
 * NotConfigured is an honest state, not a generated assistant response.
 */
sealed interface AssistantEngineStatus {
    data object NotConfigured : AssistantEngineStatus
    data class Ready(val modelId: String) : AssistantEngineStatus
    data class Unavailable(val reason: String) : AssistantEngineStatus
}

data class AssistantTurnRequest(
    val conversation: List<ChatMessage>,
    val approvedMemories: List<UserMemory>,
    val toolProviders: List<ToolProvider> = emptyList()
)

data class AssistantToolCall(
    val name: String,
    val arguments: Map<String, Any?>,
    val response: CompletableDeferred<String> = CompletableDeferred()
)

sealed interface AssistantStreamEvent {
    data class TextDelta(val text: String) : AssistantStreamEvent
    data class ToolCall(val call: AssistantToolCall) : AssistantStreamEvent
    data object Completed : AssistantStreamEvent
}

/**
 * Contract for a real model runtime.
 *
 * Implementations must support coroutine cancellation and must not perform
 * device actions or network access without a separately authorized tool layer.
 */
interface AssistantEngine {
    val status: StateFlow<AssistantEngineStatus>

    fun generate(request: AssistantTurnRequest): Flow<AssistantStreamEvent>

    suspend fun close()
}

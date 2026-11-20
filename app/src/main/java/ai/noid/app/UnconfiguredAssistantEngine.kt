package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Default engine until a real local or explicitly configured remote runtime
 * is integrated. It intentionally produces no assistant text.
 */
class UnconfiguredAssistantEngine : AssistantEngine {
    private val mutableStatus =
        MutableStateFlow<AssistantEngineStatus>(AssistantEngineStatus.NotConfigured)

    override val status: StateFlow<AssistantEngineStatus> = mutableStatus

    override fun generate(request: AssistantTurnRequest): Flow<AssistantStreamEvent> {
        throw IllegalStateException("No AI model runtime has been configured.")
    }

    override suspend fun close() = Unit
}

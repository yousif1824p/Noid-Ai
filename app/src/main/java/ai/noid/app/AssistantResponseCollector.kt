package ai.noid.app

import kotlinx.coroutines.flow.collect

/**
 * Collects a single complete model turn. Partial output is never persisted
 * as a finished assistant message, and the output size is strictly bounded.
 */
class AssistantResponseCollector {
    suspend fun collectCompleteResponse(
        engine: AssistantEngine,
        request: AssistantTurnRequest,
        onToolCall: suspend (AssistantToolCall) -> Unit = {}
    ): String {
        check(engine.status.value is AssistantEngineStatus.Ready) {
            "The AI engine is not ready."
        }

        val response = StringBuilder()
        var completed = false

        engine.generate(request).collect { event ->
            when (event) {
                is AssistantStreamEvent.TextDelta -> {
                    check(!completed) { "The model emitted text after completion." }
                    require(event.text.length <= MAX_RESPONSE_CHARS - response.length) {
                        "The model response exceeded the maximum length."
                    }
                    response.append(event.text)
                }
                is AssistantStreamEvent.ToolCall -> {
                    check(!completed) { "The model requested a tool after completion." }
                    onToolCall(event.call)
                }
                AssistantStreamEvent.Completed -> {
                    check(!completed) { "The model emitted more than one completion event." }
                    completed = true
                }
            }
        }

        check(completed) { "The model stream ended without a completion event." }
        check(response.isNotEmpty()) { "The model returned an empty response." }
        return response.toString()
    }

    companion object {
        const val MAX_RESPONSE_CHARS = 64_000
    }
}

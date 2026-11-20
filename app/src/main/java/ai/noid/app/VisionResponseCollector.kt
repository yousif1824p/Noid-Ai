package ai.noid.app

import kotlinx.coroutines.flow.collect

/**
 * Validates a complete vision response before a caller presents or persists it.
 * Partial output is never returned as a completed analysis, and output is bounded
 * so a faulty adapter cannot grow memory without limit.
 */
class VisionResponseCollector {
    suspend fun collectCompleteResponse(
        engine: VisionEngine,
        request: VisionAnalysisRequest
    ): String {
        val status = engine.status.value
        check(status is VisionEngineStatus.Ready && status.modelId.isNotBlank()) {
            "The vision engine is not ready."
        }
        VisionInputPolicy.validate(request)?.let { reason ->
            throw IllegalArgumentException(reason)
        }

        val response = StringBuilder()
        var completed = false

        engine.analyze(request).collect { event ->
            when (event) {
                is VisionStreamEvent.TextDelta -> {
                    check(!completed) { "The vision model emitted text after completion." }
                    require(event.text.length <= MAX_RESPONSE_CHARS - response.length) {
                        "The vision response exceeded the maximum length."
                    }
                    response.append(event.text)
                }
                VisionStreamEvent.Completed -> {
                    check(!completed) { "The vision model emitted more than one completion event." }
                    completed = true
                }
            }
        }

        check(completed) { "The vision stream ended without a completion event." }
        check(response.isNotEmpty()) { "The vision model returned an empty response." }
        return response.toString()
    }

    companion object {
        const val MAX_RESPONSE_CHARS = 32_000
    }
}

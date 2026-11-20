package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssistantResponseCollectorTest {
    @Test
    fun joinsTextDeltasOnlyAfterCompletion() = runBlocking {
        val engine = TestAssistantEngine(
            AssistantEngineStatus.Ready("test-model"),
            flowOf(
                AssistantStreamEvent.TextDelta("مرحباً "),
                AssistantStreamEvent.TextDelta("بك"),
                AssistantStreamEvent.Completed
            )
        )

        val response = AssistantResponseCollector().collectCompleteResponse(
            engine,
            AssistantTurnRequest(emptyList(), emptyList())
        )

        assertEquals("مرحباً بك", response)
    }

    @Test
    fun rejectsStreamWithoutCompletionEvent() {
        val engine = TestAssistantEngine(
            AssistantEngineStatus.Ready("test-model"),
            flowOf(AssistantStreamEvent.TextDelta("جزء من الرد"))
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AssistantResponseCollector().collectCompleteResponse(
                    engine,
                    AssistantTurnRequest(emptyList(), emptyList())
                )
            }
        }
    }

    @Test
    fun refusesGenerationWhenEngineIsNotReady() {
        val engine = TestAssistantEngine(
            AssistantEngineStatus.NotConfigured,
            flowOf(AssistantStreamEvent.Completed)
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AssistantResponseCollector().collectCompleteResponse(
                    engine,
                    AssistantTurnRequest(emptyList(), emptyList())
                )
            }
        }
    }

    @Test
    fun deliversToolCallToApprovalCallbackBeforeCompletion() = runBlocking {
        val call = AssistantToolCall("read_project_file", mapOf("path" to "src/Main.kt"))
        val engine = TestAssistantEngine(
            AssistantEngineStatus.Ready("test-model"),
            flowOf(AssistantStreamEvent.ToolCall(call), AssistantStreamEvent.TextDelta("تم"), AssistantStreamEvent.Completed)
        )
        var approvedName: String? = null
        val response = AssistantResponseCollector().collectCompleteResponse(
            engine, AssistantTurnRequest(emptyList(), emptyList())
        ) { received ->
            approvedName = received.name
            received.response.complete("{\"ok\":true}")
        }
        assertEquals("read_project_file", approvedName)
        assertEquals("تم", response)
        assertEquals("{\"ok\":true}", call.response.await())
    }

    @Test
    fun rejectsResponseAboveTheConfiguredLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                val engine = TestAssistantEngine(
                    AssistantEngineStatus.Ready("test-model"),
                    flow {
                        emit(AssistantStreamEvent.TextDelta("x".repeat(AssistantResponseCollector.MAX_RESPONSE_CHARS)))
                        emit(AssistantStreamEvent.TextDelta("y"))
                        emit(AssistantStreamEvent.Completed)
                    }
                )
                AssistantResponseCollector().collectCompleteResponse(
                    engine,
                    AssistantTurnRequest(emptyList(), emptyList())
                )
            }
        }
    }

    @Test
    fun rejectsEmptyCompletedResponse() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                val engine = TestAssistantEngine(
                    AssistantEngineStatus.Ready("test-model"),
                    flowOf(AssistantStreamEvent.Completed)
                )
                AssistantResponseCollector().collectCompleteResponse(
                    engine,
                    AssistantTurnRequest(emptyList(), emptyList())
                )
            }
        }
    }

    private class TestAssistantEngine(
        initialStatus: AssistantEngineStatus,
        private val events: Flow<AssistantStreamEvent>
    ) : AssistantEngine {
        override val status = MutableStateFlow(initialStatus)

        override fun generate(request: AssistantTurnRequest): Flow<AssistantStreamEvent> =
            events

        override suspend fun close() = Unit
    }
}

package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VisionResponseCollectorTest {
    private val request = VisionAnalysisRequest(
        prompt = "Describe the image",
        image = VisionImageInput("content://picker/image/1", "image/png", 1024)
    )

    @Test
    fun returnsOnlyACompletedAnalysis(): Unit {
        runBlocking {
            val engine = FakeVisionEngine {
                flow {
                    emit(VisionStreamEvent.TextDelta("A "))
                    emit(VisionStreamEvent.TextDelta("tree"))
                    emit(VisionStreamEvent.Completed)
                }
            }
            assertEquals("A tree", VisionResponseCollector().collectCompleteResponse(engine, request))
        }
    }

    @Test
    fun rejectsIncompleteStream(): Unit {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                val engine = FakeVisionEngine {
                    flow { emit(VisionStreamEvent.TextDelta("partial")) }
                }
                VisionResponseCollector().collectCompleteResponse(engine, request)
            }
        }
    }

    @Test
    fun rejectsInvalidImageBeforeCallingEngine(): Unit {
        var called = false
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                val engine = FakeVisionEngine {
                    called = true
                    flow { emit(VisionStreamEvent.Completed) }
                }
                val invalid = request.copy(
                    image = request.image.copy(contentUri = "/private/image.png")
                )
                VisionResponseCollector().collectCompleteResponse(engine, invalid)
            }
        }
        assertEquals(false, called)
    }

    @Test
    fun rejectsEngineThatIsNotReady(): Unit {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                val engine = FakeVisionEngine(
                    status = VisionEngineStatus.NotConfigured
                ) { flow { emit(VisionStreamEvent.Completed) } }
                VisionResponseCollector().collectCompleteResponse(engine, request)
            }
        }
    }

    @Test
    fun rejectsReadyEngineWithoutModelId(): Unit {
        var called = false
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                val engine = FakeVisionEngine(
                    status = VisionEngineStatus.Ready(" ")
                ) {
                    called = true
                    flow { emit(VisionStreamEvent.Completed) }
                }
                VisionResponseCollector().collectCompleteResponse(engine, request)
            }
        }
        assertEquals(false, called)
    }

    @Test
    fun rejectsResponseLargerThanConfiguredLimit(): Unit {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                val engine = FakeVisionEngine {
                    flow {
                        emit(VisionStreamEvent.TextDelta("x".repeat(VisionResponseCollector.MAX_RESPONSE_CHARS)))
                        emit(VisionStreamEvent.TextDelta("y"))
                        emit(VisionStreamEvent.Completed)
                    }
                }
                VisionResponseCollector().collectCompleteResponse(engine, request)
            }
        }
    }

    @Test
    fun rejectsEmptyCompletedResponse(): Unit {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                val engine = FakeVisionEngine {
                    flow { emit(VisionStreamEvent.Completed) }
                }
                VisionResponseCollector().collectCompleteResponse(engine, request)
            }
        }
    }

    @Test
    fun rejectsDuplicateCompletionEvents(): Unit {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                val engine = FakeVisionEngine {
                    flow {
                        emit(VisionStreamEvent.TextDelta("done"))
                        emit(VisionStreamEvent.Completed)
                        emit(VisionStreamEvent.Completed)
                    }
                }
                VisionResponseCollector().collectCompleteResponse(engine, request)
            }
        }
    }

    @Test
    fun acceptsResponseAtExactMaximumLength(): Unit {
        runBlocking {
            val expected = "x".repeat(VisionResponseCollector.MAX_RESPONSE_CHARS)
            val engine = FakeVisionEngine {
                flow {
                    emit(VisionStreamEvent.TextDelta(expected))
                    emit(VisionStreamEvent.Completed)
                }
            }
            assertEquals(expected, VisionResponseCollector().collectCompleteResponse(engine, request))
        }
    }

    @Test
    fun rejectsTextAfterCompletion(): Unit {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                val engine = FakeVisionEngine {
                    flow {
                        emit(VisionStreamEvent.TextDelta("done"))
                        emit(VisionStreamEvent.Completed)
                        emit(VisionStreamEvent.TextDelta("late"))
                    }
                }
                VisionResponseCollector().collectCompleteResponse(engine, request)
            }
        }
    }

    private class FakeVisionEngine(
        status: VisionEngineStatus = VisionEngineStatus.Ready("test-vision"),
        private val producer: () -> Flow<VisionStreamEvent>
    ) : VisionEngine {
        override val status = MutableStateFlow(status)

        override fun analyze(request: VisionAnalysisRequest): Flow<VisionStreamEvent> =
            producer()

        override suspend fun close() = Unit
    }
}

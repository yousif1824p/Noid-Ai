package ai.noid.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CancellationException

class RemoteChatTurnAdapterTest {
    @Test
    fun sendsOnlyBoundedConversationTextAndCollectsDeltas() = runBlocking {
        var captured: RemoteChatRequest? = null
        var capturedEnabled = false
        var capturedInitiated = false
        val adapter = RemoteChatTurnAdapter(RemoteChatCallFactory { enabled, initiated, _, request ->
            captured = request
            capturedEnabled = enabled
            capturedInitiated = initiated
            FakeCall(listOf(delta(request.requestId, "أهلاً "), delta(request.requestId, "بك")))
        })

        val answer = adapter.execute(
            conversation = listOf(
                ChatMessage("سؤال المستخدم", true),
                ChatMessage("رد سابق", false)
            ),
            endpoint = "https://noid.example/chat",
            enabled = true,
            userInitiated = true
        )

        assertEquals("أهلاً بك", answer)
        assertTrue(capturedEnabled)
        assertTrue(capturedInitiated)
        assertEquals(listOf("user", "assistant"), captured!!.messages.map { it.role })
        assertEquals(listOf("سؤال المستخدم", "رد سابق"), captured!!.messages.map { it.content })
    }

    @Test
    fun rejectsDisabledConnectorBeforeCreatingCall() = runBlocking {
        var factoryCalled = false
        val adapter = RemoteChatTurnAdapter(RemoteChatCallFactory { _, _, _, _ ->
            factoryCalled = true
            FakeCall(emptyList())
        })

        try {
            adapter.execute(listOf(ChatMessage("سؤال", true)), "https://noid.example", false, true)
            throw AssertionError("Expected disabled connector to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        assertFalse(factoryCalled)
    }

    @Test
    fun rejectsNonUserInitiatedRequestBeforeCreatingCall() = runBlocking {
        var factoryCalled = false
        val adapter = RemoteChatTurnAdapter(RemoteChatCallFactory { _, _, _, _ ->
            factoryCalled = true
            FakeCall(emptyList())
        })

        try {
            adapter.execute(listOf(ChatMessage("سؤال", true)), "https://noid.example", true, false)
            throw AssertionError("Expected explicit user initiation to be required.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        assertFalse(factoryCalled)
    }

    @Test
    fun convertsRemoteErrorEventToLocalFailure() = runBlocking {
        val adapter = RemoteChatTurnAdapter(RemoteChatCallFactory { _, _, _, request ->
            FakeCall(listOf(
                event(request.requestId, "started", 1),
                RemoteSseDocumentValidator.ValidatedEvent(
                    RemoteSseFrame("error", """{"request_id":"${request.requestId}"}"""),
                    RemoteSseEnvelope(request.requestId, "1", 2, errorCode = "unavailable")
                )
            ))
        })

        try {
            adapter.execute(listOf(ChatMessage("سؤال", true)), "https://noid.example", true, true)
            throw AssertionError("Expected remote error.")
        } catch (failure: IOException) {
            assertTrue(failure.message!!.contains("unavailable"))
        }
    }

    @Test
    fun cancelForwardsToActiveCall() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val adapter = RemoteChatTurnAdapter(RemoteChatCallFactory { _, _, _, _ ->
            object : RemoteChatCall {
                override fun execute(): List<RemoteSseDocumentValidator.ValidatedEvent> {
                    started.countDown()
                    while (!cancelled.get()) Thread.sleep(5)
                    return emptyList()
                }
                override fun cancel() { cancelled.set(true) }
            }
        })

        val running = async(Dispatchers.Default) {
            try {
                adapter.execute(listOf(ChatMessage("سؤال", true)), "https://noid.example", true, true)
            } catch (_: CancellationException) {
                "cancelled"
            } catch (_: IOException) {
                "cancelled"
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        adapter.cancel()
        assertEquals("cancelled", running.await())
        assertTrue(cancelled.get())
    }

    @Test
    fun cancellationDuringCallCreationCancelsBeforeExecutionStarts() = runBlocking {
        val factoryStarted = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val callCancelled = AtomicBoolean(false)
        val callExecuted = AtomicBoolean(false)
        val adapter = RemoteChatTurnAdapter(RemoteChatCallFactory { _, _, _, _ ->
            factoryStarted.countDown()
            check(releaseFactory.await(2, TimeUnit.SECONDS))
            object : RemoteChatCall {
                override fun execute(): List<RemoteSseDocumentValidator.ValidatedEvent> {
                    callExecuted.set(true)
                    return emptyList()
                }
                override fun cancel() {
                    callCancelled.set(true)
                }
            }
        })

        val running = async(Dispatchers.Default) {
            try {
                adapter.execute(
                    listOf(ChatMessage("سؤال", true)),
                    "https://noid.example",
                    true,
                    true
                )
                "unexpected-success"
            } catch (_: CancellationException) {
                "cancelled"
            }
        }

        assertTrue(factoryStarted.await(2, TimeUnit.SECONDS))
        adapter.cancel()
        releaseFactory.countDown()

        assertEquals("cancelled", running.await())
        assertTrue(callCancelled.get())
        assertFalse(callExecuted.get())
    }

    @Test
    fun adapterCanStartANewTurnAfterThePreviousTurnWasCancelled() = runBlocking {
        val firstCallStarted = CountDownLatch(1)
        val firstCallCancelled = AtomicBoolean(false)
        val factoryCalls = AtomicInteger(0)
        val adapter = RemoteChatTurnAdapter(RemoteChatCallFactory { _, _, _, request ->
            if (factoryCalls.incrementAndGet() == 1) {
                object : RemoteChatCall {
                    override fun execute(): List<RemoteSseDocumentValidator.ValidatedEvent> {
                        firstCallStarted.countDown()
                        while (!firstCallCancelled.get()) Thread.sleep(5)
                        return emptyList()
                    }
                    override fun cancel() {
                        firstCallCancelled.set(true)
                    }
                }
            } else {
                FakeCall(listOf(delta(request.requestId, "اكتمل الطلب الجديد")))
            }
        })

        val firstTurn = async(Dispatchers.Default) {
            try {
                adapter.execute(
                    listOf(ChatMessage("الطلب الأول", true)),
                    "https://noid.example",
                    true,
                    true
                )
                "unexpected-success"
            } catch (_: CancellationException) {
                "cancelled"
            }
        }

        assertTrue(firstCallStarted.await(2, TimeUnit.SECONDS))
        adapter.cancel()
        assertEquals("cancelled", firstTurn.await())

        val secondAnswer = adapter.execute(
            listOf(ChatMessage("الطلب الثاني", true)),
            "https://noid.example",
            true,
            true
        )

        assertEquals("اكتمل الطلب الجديد", secondAnswer)
        assertEquals(2, factoryCalls.get())
    }

    private fun delta(requestId: String, text: String) =
        RemoteSseDocumentValidator.ValidatedEvent(
            RemoteSseFrame("delta", ""),
            RemoteSseEnvelope(requestId, "1", 2, text = text)
        )

    private fun event(requestId: String, name: String, sequence: Long) =
        RemoteSseDocumentValidator.ValidatedEvent(
            RemoteSseFrame(name, ""),
            RemoteSseEnvelope(requestId, "1", sequence)
        )

    private class FakeCall(
        private val events: List<RemoteSseDocumentValidator.ValidatedEvent>
    ) : RemoteChatCall {
        override fun execute(): List<RemoteSseDocumentValidator.ValidatedEvent> = events
        override fun cancel() = Unit
    }
}

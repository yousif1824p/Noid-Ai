package ai.noid.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRuntimeAccessGateTest {
    @Test
    fun replacementWaitsUntilActiveConversationReleasesRuntime() = runBlocking {
        val gate = ModelRuntimeAccessGate()
        val conversationStarted = CompletableDeferred<Unit>()
        val finishConversation = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()

        val conversation = launch {
            gate.withAccess {
                conversationStarted.complete(Unit)
                finishConversation.await()
            }
        }
        conversationStarted.await()

        val replacement = launch {
            gate.withAccess { replacementStarted.complete(Unit) }
        }
        yield()
        assertFalse(replacementStarted.isCompleted)

        finishConversation.complete(Unit)
        conversation.join()
        replacement.join()
        assertTrue(replacementStarted.isCompleted)
    }

    @Test
    fun cancellationReleasesRuntimeGate() = runBlocking {
        val gate = ModelRuntimeAccessGate()
        val entered = CompletableDeferred<Unit>()
        val active = launch {
            gate.withAccess {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        active.cancelAndJoin()

        var acquired = false
        gate.withAccess { acquired = true }
        assertTrue(acquired)
    }
}

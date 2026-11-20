package ai.noid.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingModelToolCallHandoffTest {
    @Test
    fun approvalClaimsResponseBeforeToolExecutionAndPreventsLatePosting() = runBlocking {
        val handoff = PendingModelToolCallHandoff()
        val response = CompletableDeferred<String>()
        handoff.register("approval-1", response)

        val claimed = handoff.claim("approval-1")

        assertNotNull(claimed)
        assertTrue(claimed === response)
        assertEquals(0, handoff.size)
        assertNull(handoff.claim("approval-1"))
        assertTrue(response.complete("""{"ok":true}"""))
        assertEquals("""{"ok":true}""", response.await())
    }

    @Test
    fun rejectionCompletesModelCallAndRemovesPendingEntry() = runBlocking {
        val handoff = PendingModelToolCallHandoff()
        val response = CompletableDeferred<String>()
        handoff.register("approval-2", response)

        assertTrue(handoff.reject("approval-2", """{"ok":false,"error":"rejected"}"""))
        assertEquals("""{"ok":false,"error":"rejected"}""", response.await())
        assertEquals(0, handoff.size)
        assertFalse(handoff.reject("approval-2", "{}"))
    }

    @Test
    fun timeoutFollowedByGenerationCleanupCancelsApprovalAndUnblocksCall() = runBlocking {
        val handoff = PendingModelToolCallHandoff()
        val response = CompletableDeferred<String>()
        handoff.register("approval-timeout", response)

        try {
            withTimeout(20) { response.await() }
            throw AssertionError("Expected the model tool wait to time out.")
        } catch (_: TimeoutCancellationException) {
            // The generation's finally block owns cleanup after timeout.
        }

        val cancelled = mutableListOf<String>()
        assertEquals(1, handoff.cancelAll { cancelled += it })
        assertEquals(listOf("approval-timeout"), cancelled)
        assertTrue(response.isCompleted)
        assertEquals(0, handoff.size)
    }

    @Test
    fun generationCancellationCleanupCancelsAllOutstandingApprovals() {
        val handoff = PendingModelToolCallHandoff()
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        handoff.register("approval-a", first)
        handoff.register("approval-b", second)
        val cancelled = mutableListOf<String>()

        assertEquals(2, handoff.cancelAll { cancelled += it })

        assertEquals(setOf("approval-a", "approval-b"), cancelled.toSet())
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertEquals(0, handoff.size)
    }

    @Test
    fun duplicateApprovalIdCannotReplaceExistingModelResponse() {
        val handoff = PendingModelToolCallHandoff()
        val first = CompletableDeferred<String>()
        handoff.register("duplicate", first)

        try {
            handoff.register("duplicate", CompletableDeferred())
            throw AssertionError("Expected duplicate registration to fail.")
        } catch (_: IllegalStateException) {
            assertTrue(handoff.claim("duplicate") === first)
        }
    }
}

package ai.noid.app

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfDevelopmentApplyRollbackTest {
    @Test
    fun cancellationDuringReadBackCompletesRollbackBeforeJobFinishes() = runBlocking {
        val readStarted = CompletableDeferred<Unit>()
        val rollbackStarted = CompletableDeferred<Unit>()
        var rollbackCompleted = false

        val job = launch {
            readBackWithRollback(
                readBack = {
                    readStarted.complete(Unit)
                    awaitCancellation()
                },
                rollback = {
                    rollbackStarted.complete(Unit)
                    delay(10)
                    rollbackCompleted = true
                }
            )
        }

        readStarted.await()
        job.cancelAndJoin()

        assertTrue(rollbackStarted.isCompleted)
        assertTrue("Rollback must finish before cancellation escapes", rollbackCompleted)
    }

    @Test
    fun readBackFailureRollsBackAndReturnsVerificationFailure() = runBlocking {
        var rollbackCompleted = false

        try {
            readBackWithRollback<String>(
                readBack = { throw IOException("read-back unavailable") },
                rollback = { rollbackCompleted = true }
            )
            throw AssertionError("Expected verification failure")
        } catch (_: ApplyVerificationException) {
            assertTrue(rollbackCompleted)
        }
    }

    @Test
    fun rollbackFailureIsNotHiddenAfterReadBackFailure() = runBlocking {
        try {
            readBackWithRollback<String>(
                readBack = { throw IOException("read-back unavailable") },
                rollback = { throw IOException("rollback unavailable") }
            )
            throw AssertionError("Expected rollback failure")
        } catch (failure: IOException) {
            assertEquals("rollback unavailable", failure.message)
        }
    }
}

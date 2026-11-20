package ai.noid.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionCoordinatorTest {
    private val action = ToolAction(
        "read-project",
        ToolCapability.READ_FILES,
        ToolRisk.READ_ONLY,
        "قراءة ملف مشروع محدد"
    )

    @Test
    fun deniedActionIsNotExecutedAndDenialIsAudited() = runBlocking {
        val dao = FakeDao()
        var invoked = false
        val result = ToolExecutionCoordinator().execute(
            action, emptySet(), true, ToolAuditRepository(dao)
        ) { invoked = true }

        assertFalse(invoked)
        assertTrue(result is ToolExecutionResult.Rejected)
        assertEquals(ToolDecision.DENY, (result as ToolExecutionResult.Rejected).decision)
        assertEquals("DENY", dao.events.value.single().decision)
    }

    @Test
    fun failedStartAuditPreventsOperation() = runBlocking {
        val dao = FakeDao(failAtInsert = 1)
        var invoked = false
        val result = ToolExecutionCoordinator().execute(
            action, setOf(ToolCapability.READ_FILES), false, ToolAuditRepository(dao)
        ) { invoked = true }

        assertFalse(invoked)
        assertEquals("start", (result as ToolExecutionResult.AuditUnavailable).phase)
    }

    @Test
    fun successfulOperationRecordsStartAndCompletion() = runBlocking {
        val dao = FakeDao()
        val result = ToolExecutionCoordinator().execute(
            action, setOf(ToolCapability.READ_FILES), false, ToolAuditRepository(dao)
        ) { "contents" }

        assertEquals(ToolExecutionResult.Completed("contents", true), result)
        assertEquals(listOf("started", "completed"), dao.events.value.reversed().map { it.details })
    }

    @Test
    fun operationFailureIsReportedAndAudited() = runBlocking {
        val dao = FakeDao()
        val result = ToolExecutionCoordinator().execute(
            action, setOf(ToolCapability.READ_FILES), false, ToolAuditRepository(dao)
        ) { error("private failure detail") }

        assertEquals(
            ToolExecutionResult.Failed("IllegalStateException", true),
            result
        )
        assertEquals("failed: IllegalStateException", dao.events.value.first().details)
        assertFalse(dao.events.value.any { it.details?.contains("private failure detail") == true })
    }

    @Test
    fun cancellationIsPropagatedWithoutConvertingItToFailure() = runBlocking {
        val dao = FakeDao()
        val execution = async {
            ToolExecutionCoordinator().execute(
                action, setOf(ToolCapability.READ_FILES), false, ToolAuditRepository(dao)
            ) { throw CancellationException("cancelled") }
        }
        try {
            execution.await()
            throw AssertionError("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
        assertEquals(
            listOf("started", "cancelled"),
            dao.events.value.reversed().map { it.details }
        )
    }

    @Test
    fun cancellationWritesTerminalAuditEventAndStillPropagates() = runBlocking {
        val dao = FakeDao()
        try {
            ToolExecutionCoordinator().execute(
                action, setOf(ToolCapability.READ_FILES), false, ToolAuditRepository(dao)
            ) { throw CancellationException("user stopped operation") }
            throw AssertionError("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("user stopped operation", expected.message)
        }
        assertEquals(listOf("started", "cancelled"), dao.events.value.reversed().map { it.details })
    }

    @Test
    fun auditFailureDuringCancellationDoesNotMaskCancellation() = runBlocking {
        val dao = FakeDao(failAtInsert = 2)
        try {
            ToolExecutionCoordinator().execute(
                action, setOf(ToolCapability.READ_FILES), false, ToolAuditRepository(dao)
            ) { throw CancellationException("original cancellation") }
            throw AssertionError("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("original cancellation", expected.message)
        }
        assertEquals("started", dao.events.value.single().details)
    }

    @Test
    fun completionAuditFailureDoesNotHideSuccessfulOperation() = runBlocking {
        val dao = FakeDao(failAtInsert = 2)
        var invoked = false
        val result = ToolExecutionCoordinator().execute(
            action, setOf(ToolCapability.READ_FILES), false, ToolAuditRepository(dao)
        ) { invoked = true; "contents" }

        assertTrue(invoked)
        assertEquals(ToolExecutionResult.Completed("contents", false), result)
        assertEquals(listOf("started"), dao.events.value.reversed().map { it.details })
    }

    @Test
    fun failureAuditFailureDoesNotHideOriginalOperationFailure() = runBlocking {
        val dao = FakeDao(failAtInsert = 2)
        val result = ToolExecutionCoordinator().execute(
            action, setOf(ToolCapability.READ_FILES), false, ToolAuditRepository(dao)
        ) { error("private failure detail") }

        assertEquals(ToolExecutionResult.Failed("IllegalStateException", false), result)
        assertEquals(listOf("started"), dao.events.value.reversed().map { it.details })
        assertFalse(dao.events.value.any { it.details?.contains("private failure detail") == true })
    }

    private class FakeDao(
        private val failAtInsert: Int? = null
    ) : ToolAuditDao {
        val events = MutableStateFlow<List<ToolAuditEvent>>(emptyList())
        private var inserts = 0

        override fun observeRecent(): Flow<List<ToolAuditEvent>> = events

        override suspend fun insert(event: ToolAuditEvent): Long {
            inserts++
            if (inserts == failAtInsert) error("database unavailable")
            val id = inserts.toLong()
            events.value = listOf(event.copy(id = id)) + events.value
            return id
        }
    }
}

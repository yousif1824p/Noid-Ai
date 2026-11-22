package ai.noid.app

import androidx.room.Room
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskPlanRecoveryCoordinatorIntegrationTest {
    private val readAction = ToolAction(
        "project.read", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "قراءة ملف"
    )
    private val writeAction = ToolAction(
        "project.write", ToolCapability.WRITE_FILES, ToolRisk.SENSITIVE, "كتابة ملف"
    )

    @Test fun successfulExecutionIsPersistedAsCompleted() = runBlocking {
        val db = database()
        try {
            val recovery = TaskPlanRecoveryStore(db.taskPlanRecoveryDao())
            val registry = registry(readAction) { "read-ok" }
            val coordinator = AssistantTaskPlanCoordinator(registry, recovery)
            val result = coordinator.start(plan("done", readAction))
            assertTrue(result is AssistantTaskPlanProgress.Completed)
            assertEquals(
                TaskPlanRecoveryRecord.COMPLETED,
                db.taskPlanRecoveryDao().find("done")!!.state
            )
        } finally { db.close() }
    }

    @Test fun interruptedApprovalCannotBeContinuedByANewCoordinator() = runBlocking {
        val db = database()
        try {
            var writes = 0
            val registry = registry(writeAction) { writes++; "written" }
            val recovery = TaskPlanRecoveryStore(db.taskPlanRecoveryDao())
            val firstCoordinator = AssistantTaskPlanCoordinator(registry, recovery)
            val paused = firstCoordinator.start(plan("approval", writeAction))
                as AssistantTaskPlanProgress.AwaitingApproval
            assertEquals(
                TaskPlanRecoveryRecord.WAITING_APPROVAL,
                db.taskPlanRecoveryDao().find("approval")!!.state
            )

            registry.cancelApproval(paused.pending.id)
            val restartedRecovery = TaskPlanRecoveryStore(db.taskPlanRecoveryDao())
            assertEquals(1, restartedRecovery.unresolved().size)
            val restartedCoordinator = AssistantTaskPlanCoordinator(registry, restartedRecovery)
            assertTrue(
                restartedCoordinator.approve("approval", paused.pending.id)
                    is AssistantTaskPlanProgress.Rejected
            )
            assertEquals(0, writes)
            assertEquals(
                TaskPlanRecoveryRecord.INTERRUPTED,
                db.taskPlanRecoveryDao().find("approval")!!.state
            )
        } finally { db.close() }
    }

    private fun plan(id: String, action: ToolAction) = AssistantTaskPlan(
        id, "اختبار استعادة المنسق",
        listOf(AssistantTaskPlanStep(
            "step-1", AssistantToolProposal(action.actionId, emptyMap())
        ))
    )

    private fun registry(action: ToolAction, execute: () -> String): ToolRegistry =
        ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(FakeAuditDao())).also { registry ->
            registry.register(object : NoidToolAdapter {
                override val action = action
                override val modelToolName = "test_" + action.actionId.replace('.', '_')
                override val modelToolDescriptionJson =
                    """{"name":"$modelToolName","parameters":{"type":"object"}}"""
                override fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? =
                    arguments.mapValues { (_, value) -> value as? String ?: return null }
                override fun validate(request: ToolRequest): String? = null
                override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> =
                    setOf(action.capability)
                override suspend fun execute(request: ToolRequest): String = execute()
            })
        }

    private fun database() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), NoidDatabase::class.java
    ).allowMainThreadQueries().build()

    private class FakeAuditDao : ToolAuditDao {
        private val events = MutableStateFlow<List<ToolAuditEvent>>(emptyList())
        override fun observeRecent(): Flow<List<ToolAuditEvent>> = events
        override suspend fun insert(event: ToolAuditEvent): Long {
            val id = events.value.size + 1L
            events.value = listOf(event.copy(id = id)) + events.value
            return id
        }
    }
}

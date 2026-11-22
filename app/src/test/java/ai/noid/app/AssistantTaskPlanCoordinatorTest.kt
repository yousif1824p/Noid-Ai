package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTaskPlanCoordinatorTest {
    private val readAction = ToolAction("project.read", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "قراءة ملف مشروع محدد")
    private val writeAction = ToolAction("project.write", ToolCapability.WRITE_FILES, ToolRisk.SENSITIVE, "كتابة ملف بعد موافقة المستخدم")

    @Test fun executesSafeStepsInOrderAndCompletesPlan() = runBlocking {
        val calls = mutableListOf<String>()
        val registry = registry().also {
            it.register(adapter(readAction) { calls += "read"; "read-result" })
            it.register(adapter(writeAction) { calls += "write"; "write-result" })
        }
        val result = AssistantTaskPlanCoordinator(registry).start(
            plan(step("one", readAction), step("two", readAction))
        )
        assertTrue(result is AssistantTaskPlanProgress.Completed)
        assertEquals(listOf("read", "read"), calls)
        assertEquals(2, (result as AssistantTaskPlanProgress.Completed).results.size)
    }

    @Test fun sensitiveStepPausesUntilApprovalThenContinuesSequentially() = runBlocking {
        val calls = mutableListOf<String>()
        val registry = registry().also {
            it.register(adapter(readAction) { calls += "read"; "read" })
            it.register(adapter(writeAction) { calls += "write"; "written" })
        }
        val coordinator = AssistantTaskPlanCoordinator(registry)
        val paused = coordinator.start(plan(step("read", readAction), step("write", writeAction), step("finish", readAction)))
        assertTrue(paused is AssistantTaskPlanProgress.AwaitingApproval)
        val approval = paused as AssistantTaskPlanProgress.AwaitingApproval
        assertEquals(1, approval.stepIndex)
        assertEquals(listOf("read"), calls)
        assertEquals(1, registry.pendingApprovals.value.size)

        val completed = coordinator.approve(approval.planId, approval.pending.id)
        assertTrue(completed is AssistantTaskPlanProgress.Completed)
        assertEquals(listOf("read", "write", "read"), calls)
        assertEquals(3, (completed as AssistantTaskPlanProgress.Completed).results.size)
        assertTrue(registry.pendingApprovals.value.isEmpty())
    }

    @Test fun rejectsEntirePlanBeforeExecutingAnyStepWhenLaterStepIsInvalid() = runBlocking {
        var executions = 0
        val registry = registry().also { it.register(adapter(readAction) { executions++; "read" }) }
        val invalid = AssistantTaskPlan(
            "invalid-plan", "اختبار رفض الخطة",
            listOf(step("valid", readAction), AssistantTaskPlanStep("invalid", AssistantToolProposal("unknown.action", emptyMap())))
        )
        val result = AssistantTaskPlanCoordinator(registry).start(invalid)
        assertTrue(result is AssistantTaskPlanProgress.Rejected)
        assertEquals(0, executions)
    }

    @Test fun failedStepStopsPlanAndDoesNotRunFollowingSteps() = runBlocking {
        val calls = mutableListOf<String>()
        val registry = registry().also {
            it.register(adapter(readAction) { calls += "fail"; error("private failure detail") })
        }
        val result = AssistantTaskPlanCoordinator(registry).start(plan(step("failure", readAction), step("never", readAction)))
        assertTrue(result is AssistantTaskPlanProgress.Stopped)
        assertEquals(listOf("fail"), calls)
        assertFalse((result as AssistantTaskPlanProgress.Stopped).reason.contains("private"))
    }

    @Test fun rejectingApprovalCancelsTicketAndStopsRemainingSteps() = runBlocking {
        var writes = 0
        val registry = registry().also {
            it.register(adapter(readAction) { "read" })
            it.register(adapter(writeAction) { writes++; "written" })
        }
        val coordinator = AssistantTaskPlanCoordinator(registry)
        val paused = coordinator.start(plan(step("write", writeAction), step("later", readAction)))
            as AssistantTaskPlanProgress.AwaitingApproval
        val stopped = coordinator.reject(paused.planId)
        assertTrue(stopped is AssistantTaskPlanProgress.Stopped)
        assertEquals(0, writes)
        assertTrue(registry.pendingApprovals.value.isEmpty())
    }

    @Test fun invalidBoundsAndDuplicateStepIdsAreRejected() = runBlocking {
        val registry = registry().also { it.register(adapter(readAction) { "read" }) }
        val coordinator = AssistantTaskPlanCoordinator(registry)
        val duplicate = AssistantTaskPlan("duplicate", "خطة اختبار", listOf(step("same", readAction), step("same", readAction)))
        assertTrue(coordinator.start(duplicate) is AssistantTaskPlanProgress.Rejected)
        val oversized = (0..AssistantTaskPlanPolicy.MAX_STEPS).map { step("step-$it", readAction) }
        assertTrue(coordinator.start(AssistantTaskPlan("oversized", "خطة اختبار", oversized)) is AssistantTaskPlanProgress.Rejected)
    }

    @Test fun duplicateActivePlanIdIsRejected() = runBlocking {
        val registry = registry().also { it.register(adapter(writeAction) { "written" }) }
        val coordinator = AssistantTaskPlanCoordinator(registry)
        val first = coordinator.start(plan(step("write", writeAction)))
        assertTrue(first is AssistantTaskPlanProgress.AwaitingApproval)
        assertTrue(coordinator.start(plan(step("write", writeAction))) is AssistantTaskPlanProgress.Rejected)
    }


    @Test fun plannedModelToolArgumentsAreBoundByAppContextBeforeExecution() = runBlocking {
        val action = ToolAction("project.bound-read", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "قراءة ملف محدد")
        var receivedSelection: String? = null
        val registry = registry()
        registry.register(object : NoidToolAdapter {
            override val action = action
            override val modelToolName = "bound_read"
            override val modelToolDescriptionJson = """{"name":"bound_read","parameters":{"type":"object"}}"""
            override fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? {
                val path = arguments["path"] as? String ?: return null
                return mapOf("path" to path, "selectionId" to "selection-from-app")
            }
            override fun validate(request: ToolRequest): String? =
                if (request.arguments["selectionId"] == "selection-from-app") null else "missing app context"
            override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> =
                setOf(ToolCapability.READ_FILES)
            override suspend fun execute(request: ToolRequest): String {
                receivedSelection = request.arguments["selectionId"]
                return "read"
            }
        })
        val plan = AssistantTaskPlan(
            "bound-plan",
            "قراءة ملف",
            listOf(AssistantTaskPlanStep(
                "read",
                AssistantToolProposal(action.actionId, mapOf("path" to "README.md"), "قراءة")
            ))
        )
        val result = AssistantTaskPlanCoordinator(registry).start(plan)
        assertTrue(result is AssistantTaskPlanProgress.Completed)
        assertEquals("selection-from-app", receivedSelection)
    }

    private fun plan(vararg steps: AssistantTaskPlanStep) = AssistantTaskPlan("test-plan", "تنفيذ مهمة اختبارية", steps.toList())
    private fun step(id: String, action: ToolAction) =
        AssistantTaskPlanStep(id, AssistantToolProposal(action.actionId, emptyMap(), "خطوة اختبار"))

    private fun registry() = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(FakeAuditDao()))

    private fun adapter(toolAction: ToolAction, executeBlock: suspend () -> String) = object : NoidToolAdapter {
        override val action: ToolAction = toolAction
        override val modelToolName: String = "test_" + toolAction.actionId.replace(Regex("[^A-Za-z0-9_]"), "_")
        override val modelToolDescriptionJson: String =
            """{"name":"$modelToolName","parameters":{"type":"object"}}"""
        override fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? =
            arguments.mapValues { (_, value) -> value as? String ?: return null }
        override fun validate(request: ToolRequest): String? = null
        override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> = setOf(toolAction.capability)
        override suspend fun execute(request: ToolRequest): String = executeBlock()
    }

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

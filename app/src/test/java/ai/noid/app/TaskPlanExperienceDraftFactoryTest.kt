package ai.noid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlanExperienceDraftFactoryTest {
    @Test fun createsMetadataOnlyDraftForFullyAuditedPlan() {
        val draft = TaskPlanExperienceDraftFactory.from(completed())

        assertNotNull(draft)
        assertTrue(draft!!.content.contains("project.read ← project.inspect"))
        assertFalse(draft.content.contains("PRIVATE_FILE_CONTENT"))
        assertFalse(draft.content.contains("secret/path"))
        assertTrue(draft.content.contains("لا تتضمن معاملات التنفيذ"))
    }

    @Test fun refusesDraftWhenAnyStepIsNotAudited() {
        val progress = completed().copy(
            results = listOf(
                ToolRegistryResult.Executed(ToolExecutionResult.Completed("safe", true)),
                ToolRegistryResult.Executed(ToolExecutionResult.Completed("safe", false))
            )
        )
        assertNull(TaskPlanExperienceDraftFactory.from(progress))
    }

    @Test fun refusesDraftWhenStepAndResultCountsDiffer() {
        assertNull(TaskPlanExperienceDraftFactory.from(completed().copy(results = emptyList())))
    }

    @Test fun refusesInvalidActionIdentifiers() {
        assertNull(TaskPlanExperienceDraftFactory.from(completed().copy(actionIds = listOf("../private"))))
    }

    private fun completed() = AssistantTaskPlanProgress.Completed(
        planId = "plan-123",
        results = listOf(
            ToolRegistryResult.Executed(
                ToolExecutionResult.Completed("PRIVATE_FILE_CONTENT secret/path", true)
            ),
            ToolRegistryResult.Executed(
                ToolExecutionResult.Completed("PRIVATE_FILE_CONTENT secret/path", true)
            )
        ),
        actionIds = listOf("project.read", "project.inspect")
    )
}

package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantToolProposalRegistryTest {
    private val action = ToolAction(
        actionId = "project.write-text",
        capability = ToolCapability.WRITE_FILES,
        risk = ToolRisk.SENSITIVE,
        description = "كتابة ملف بعد موافقة المستخدم"
    )

    @Test
    fun validProposalCreatesPendingApprovalButDoesNotExecute() {
        var executions = 0
        val registry = registry().also { it.register(adapter { executions++; "saved" }) }

        val pending = registry.requestApproval(
            AssistantToolProposal(action.actionId, mapOf("path" to "notes.txt"), "اكتب الملف")
        )

        assertNotNull(pending)
        assertEquals(0, executions)
        assertEquals(1, registry.pendingApprovals.value.size)
    }

    @Test
    fun unknownOrPrivilegeBearingProposalCannotCreateApproval() {
        val registry = registry().also { it.register(adapter { "saved" }) }

        assertNull(
            registry.requestApproval(
                AssistantToolProposal("shell.execute", mapOf("command" to "id"))
            )
        )
        assertNull(
            registry.requestApproval(
                AssistantToolProposal(action.actionId, mapOf("AUDIT_ID" to "forged"))
            )
        )
        assertTrue(registry.pendingApprovals.value.isEmpty())
    }

    private fun registry() =
        ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(FakeAuditDao()))

    private fun adapter(executeBlock: suspend () -> String) = object : NoidToolAdapter {
        override val action = this@AssistantToolProposalRegistryTest.action
        override fun validate(request: ToolRequest): String? =
            if (request.arguments.keys == setOf("path")) null else "معاملات غير صالحة"
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

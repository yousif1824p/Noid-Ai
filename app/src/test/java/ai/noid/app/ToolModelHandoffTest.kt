package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolModelHandoffTest {
    private val action = ToolAction(
        actionId = "project-file-read",
        capability = ToolCapability.READ_FILES,
        risk = ToolRisk.SENSITIVE,
        description = "قراءة ملف بعد موافقة المستخدم"
    )

    @Test
    fun modelToolCallIsMappedToPendingApprovalWithoutExecuting() {
        var executions = 0
        val registry = registry().also { it.register(adapter { executions++; "contents" }) }
        val pending = registry.requestApprovalFromModel(
            "read_project_file", mapOf("path" to "src/Main.kt"), "قراءة الملف"
        )
        assertNotNull(pending)
        assertEquals("src/Main.kt", pending!!.arguments["path"])
        assertEquals("selected-tree-token", pending.arguments["selectionId"])
        assertEquals(0, executions)
        assertEquals(1, registry.pendingApprovals.value.size)
    }

    @Test
    fun rejectsUnknownToolNamesExtraFieldsAndNonStringArguments() {
        val registry = registry().also { it.register(adapter { "contents" }) }
        assertNull(registry.requestApprovalFromModel("shell", mapOf("command" to "id")))
        assertNull(registry.requestApprovalFromModel("read_project_file", mapOf("path" to "src/Main.kt", "uri" to "content://bad")))
        assertNull(registry.requestApprovalFromModel("read_project_file", mapOf("path" to listOf("bad"))))
        assertTrue(registry.pendingApprovals.value.isEmpty())
    }

    @Test
    fun modelSchemasAreExposedAsProviders() {
        val registry = registry().also { it.register(adapter { "contents" }) }
        assertEquals(1, registry.modelToolProviders().size)
    }

    private fun registry() =
        ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(FakeAuditDao()))

    private fun adapter(executeBlock: suspend () -> String) = object : NoidToolAdapter {
        override val action = this@ToolModelHandoffTest.action
        override val modelToolName = "read_project_file"
        override val modelToolDescriptionJson = """{"name":"read_project_file","description":"Read a selected project file","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}"""
        override fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? {
            if (arguments.keys != setOf("path")) return null
            val path = arguments["path"] as? String ?: return null
            return mapOf("selectionId" to "selected-tree-token", "path" to path)
        }
        override fun validate(request: ToolRequest): String? =
            if (request.arguments.keys == setOf("selectionId", "path")) null else "معاملات غير صالحة"
        override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> =
            setOf(ToolCapability.READ_FILES)
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

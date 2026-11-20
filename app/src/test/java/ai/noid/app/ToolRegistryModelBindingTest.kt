package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryModelBindingTest {
    private var activeSelectionId: String? = "selection-owned-by-app"
    private var executions = 0

    private val adapter = object : NoidToolAdapter {
        override val action = ToolAction(
            actionId = "project.read-selected-file",
            capability = ToolCapability.READ_FILES,
            risk = ToolRisk.SENSITIVE,
            description = "قراءة ملف من المشروع المختار"
        )
        override val modelToolName = "read_selected_project_file"
        override val modelToolDescriptionJson = """{"name":"read_selected_project_file","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}}"""

        override fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? =
            SelectedProjectModelArgumentPolicy.bindPath(
                arguments = arguments,
                activeSelectionId = activeSelectionId,
                pathArgument = "path",
                selectionArgument = "selectionId"
            )

        override fun validate(request: ToolRequest): String? =
            if (request.arguments.keys == setOf("selectionId", "path") &&
                request.arguments["selectionId"] == activeSelectionId &&
                request.arguments["path"]?.endsWith(".txt") == true
            ) null else "طلب غير صالح أو اختيار المشروع تغيّر."

        override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> =
            if (request.arguments["selectionId"] == activeSelectionId) {
                setOf(ToolCapability.READ_FILES)
            } else emptySet()

        override suspend fun execute(request: ToolRequest): String {
            executions++
            return request.arguments.getValue("path")
        }
    }

    private val dao = InMemoryAuditDao()
    private val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao)).also {
        it.register(adapter)
    }

    @Test
    fun modelPathIsBoundToAppOwnedSelectionAndCreatesApprovalTicket() {
        val pending = registry.requestApprovalFromModel(
            "read_selected_project_file",
            mapOf("path" to "docs/notes.txt")
        )

        assertTrue(pending != null)
        assertEquals("selection-owned-by-app", pending!!.arguments["selectionId"])
        assertEquals("docs/notes.txt", pending.arguments["path"])
        assertEquals(0, executions)
    }

    @Test
    fun modelCannotSupplySelectionIdUriOrAdditionalArguments() {
        assertNull(registry.requestApprovalFromModel(
            "read_selected_project_file",
            mapOf("path" to "docs/notes.txt", "selectionId" to "forged")
        ))
        assertNull(registry.requestApprovalFromModel(
            "read_selected_project_file",
            mapOf("path" to "docs/notes.txt", "uri" to "content://forged")
        ))
        assertNull(registry.requestApprovalFromModel(
            "read_selected_project_file",
            mapOf("path" to "docs/notes.txt", "extra" to "value")
        ))
        assertTrue(registry.pendingApprovals.value.isEmpty())
        assertEquals(0, executions)
    }

    @Test
    fun modelCannotSupplyNonStringOrMissingPath() {
        assertNull(registry.requestApprovalFromModel("read_selected_project_file", mapOf("path" to 42)))
        assertNull(registry.requestApprovalFromModel("read_selected_project_file", mapOf("path" to null)))
        assertNull(registry.requestApprovalFromModel("read_selected_project_file", emptyMap()))
        assertTrue(registry.pendingApprovals.value.isEmpty())
    }

    @Test
    fun missingOrChangedAppSelectionFailsClosed() {
        activeSelectionId = null
        assertNull(registry.requestApprovalFromModel(
            "read_selected_project_file",
            mapOf("path" to "docs/notes.txt")
        ))
        activeSelectionId = "  "
        assertNull(registry.requestApprovalFromModel(
            "read_selected_project_file",
            mapOf("path" to "docs/notes.txt")
        ))
        assertTrue(registry.pendingApprovals.value.isEmpty())
    }

    @Test
    fun unknownModelToolNeverCreatesApproval() {
        assertNull(registry.requestApprovalFromModel("unknown_tool", mapOf("path" to "docs/notes.txt")))
        assertTrue(registry.pendingApprovals.value.isEmpty())
    }

    private class InMemoryAuditDao : ToolAuditDao {
        private val state = MutableStateFlow<List<ToolAuditEvent>>(emptyList())
        override fun observeRecent(): Flow<List<ToolAuditEvent>> = state
        override suspend fun insert(event: ToolAuditEvent): Long {
            val id = state.value.size + 1L
            state.value = listOf(event.copy(id = id)) + state.value
            return id
        }
    }
}

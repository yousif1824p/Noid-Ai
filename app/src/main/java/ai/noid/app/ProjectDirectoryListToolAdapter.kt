package ai.noid.app

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only directory listing limited to the exact SAF tree selected by the user. */
class ProjectDirectoryListToolAdapter(
    context: Context,
    private val selectedTree: SelectedProjectTreeSession,
    private val repository: ProjectFileRepository
) : NoidToolAdapter {
    private val resolver = context.applicationContext.contentResolver

    override val action = ToolAction(
        actionId = ACTION_ID,
        capability = ToolCapability.READ_FILES,
        risk = ToolRisk.SENSITIVE,
        description = "عرض أسماء الملفات والمجلدات داخل مشروع اختاره المستخدم بعد موافقته"
    )

    override val modelToolName = MODEL_TOOL_NAME
    override val modelToolDescriptionJson = """
        {
          "name": "$MODEL_TOOL_NAME",
          "description": "اعرض أسماء الملفات والمجلدات داخل مجلد المشروع الذي اختاره المستخدم. استخدم مساراً نسبياً؛ اتركه فارغاً للجذر.",
          "parameters": {
            "type": "object",
            "properties": {
              "path": { "type": "string", "description": "مسار مجلد نسبي داخل المشروع، أو نص فارغ للجذر" }
            },
            "required": ["path"],
            "additionalProperties": false
          }
        }
    """.trimIndent()

    override fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? =
        SelectedProjectModelArgumentPolicy.bindPath(
            arguments = arguments,
            activeSelectionId = selectedTree.current()?.selectionId,
            pathArgument = ARG_PATH,
            selectionArgument = ARG_SELECTION_ID
        )

    override fun validate(request: ToolRequest): String? =
        ProjectDirectoryListRequestPolicy.validate(request.arguments, selectedTree.current()?.selectionId)

    override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> {
        val selection = selectedTree.current() ?: return emptySet()
        if (request.arguments[ARG_SELECTION_ID] != selection.selectionId) return emptySet()
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == selection.uri } ?: return emptySet()
        return if (grant.isReadPermission) setOf(ToolCapability.READ_FILES) else emptySet()
    }

    override suspend fun execute(request: ToolRequest): String {
        val selection = selectedTree.current() ?: throw IOException("لم يتم اختيار مجلد مشروع.")
        if (request.arguments[ARG_SELECTION_ID] != selection.selectionId) {
            throw IOException("تغير مجلد المشروع بعد إنشاء الطلب؛ أعد المحاولة.")
        }
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == selection.uri }
        if (grant?.isReadPermission != true) throw SecurityException("إذن قراءة مجلد المشروع غير متاح.")
        val path = request.arguments[ARG_PATH] ?: throw IOException("مسار المجلد مفقود.")
        val entries = withContext(Dispatchers.IO) { repository.list(selection.uri, path) }
        return ProjectDirectoryListOutputPolicy.format(
            entries = entries,
            maxEntries = MAX_ENTRIES,
            maxNameChars = MAX_NAME_CHARS,
            maxOutputChars = MAX_OUTPUT_CHARS
        )
    }

    companion object {
        const val ACTION_ID = "project-directory-list-model"
        const val MODEL_TOOL_NAME = "list_project_directory"
        const val ARG_SELECTION_ID = "selectionId"
        const val ARG_PATH = "path"
        const val MAX_ENTRIES = 80
        const val MAX_NAME_CHARS = 180
        const val MAX_OUTPUT_CHARS = 12_000
    }
}

object ProjectDirectoryListRequestPolicy {
    fun validate(arguments: Map<String, String>, activeSelectionId: String?): String? {
        if (arguments.keys != setOf(ProjectDirectoryListToolAdapter.ARG_SELECTION_ID, ProjectDirectoryListToolAdapter.ARG_PATH)) {
            return "معاملات طلب عرض المجلد غير معروفة."
        }
        if (activeSelectionId.isNullOrBlank()) return "اختر مجلد المشروع أولاً."
        if (arguments[ProjectDirectoryListToolAdapter.ARG_SELECTION_ID] != activeSelectionId) {
            return "طلب المجلد لا يطابق اختيار المشروع الحالي."
        }
        val path = arguments[ProjectDirectoryListToolAdapter.ARG_PATH] ?: return "مسار المجلد مفقود."
        if (path.length > MAX_PATH_LENGTH || path.contains('\\') || path.any { it.isISOControl() }) {
            return "مسار المجلد غير صالح."
        }
        if (path.isEmpty()) return null
        val normalized = ProjectFilePolicy.normalizeRelativePath(path) ?: return "مسار المجلد غير صالح."
        if (normalized != path) return "يجب أن يكون مسار المجلد نسبياً ومنسقاً."
        return null
    }
    private const val MAX_PATH_LENGTH = 512
}

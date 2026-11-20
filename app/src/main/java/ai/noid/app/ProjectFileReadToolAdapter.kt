package ai.noid.app

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SelectedProjectTreeSession {
    data class Selection(val uri: Uri, val selectionId: String)
    @Volatile private var selection: Selection? = null

    @Synchronized
    fun select(uri: Uri): Selection =
        Selection(uri, java.util.UUID.randomUUID().toString()).also { selection = it }

    fun current(): Selection? = selection
}

class ProjectFileReadToolAdapter(
    context: Context,
    private val selectedTree: SelectedProjectTreeSession,
    private val repository: ProjectFileRepository
) : NoidToolAdapter {
    private val resolver = context.applicationContext.contentResolver

    override val action = ToolAction(
        actionId = ACTION_ID,
        capability = ToolCapability.READ_FILES,
        risk = ToolRisk.SENSITIVE,
        description = "قراءة ملف نصي من مشروع اختاره المستخدم بعد موافقته"
    )

    override val modelToolName = MODEL_TOOL_NAME
    override val modelToolDescriptionJson = """
        {
          "name": "$MODEL_TOOL_NAME",
          "description": "اقرأ ملفاً نصياً من مجلد المشروع الذي اختاره المستخدم. استخدم مساراً نسبياً فقط.",
          "parameters": {
            "type": "object",
            "properties": {
              "path": {
                "type": "string",
                "description": "مسار نسبي داخل المشروع مثل app/src/main/AndroidManifest.xml"
              }
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
        ProjectFileToolRequestPolicy.validate(request.arguments, selectedTree.current()?.selectionId)

    override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> {
        val selection = selectedTree.current() ?: return emptySet()
        if (request.arguments[ARG_SELECTION_ID] != selection.selectionId) return emptySet()
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == selection.uri }
            ?: return emptySet()
        return if (grant.isReadPermission) setOf(ToolCapability.READ_FILES) else emptySet()
    }

    override suspend fun execute(request: ToolRequest): String {
        val selection = selectedTree.current()
            ?: throw IOException("لم يتم اختيار مجلد مشروع.")
        if (request.arguments[ARG_SELECTION_ID] != selection.selectionId) {
            throw IOException("تغير مجلد المشروع بعد إنشاء الطلب؛ أعد المحاولة.")
        }
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == selection.uri }
        if (grant?.isReadPermission != true) {
            throw SecurityException("إذن قراءة مجلد المشروع غير متاح.")
        }
        val path = request.arguments[ARG_PATH] ?: throw IOException("مسار الملف مفقود.")
        val content = withContext(Dispatchers.IO) { repository.readText(selection.uri, path) }
        return if (content.length <= MAX_RETURN_CHARS) content
        else content.take(MAX_RETURN_CHARS) + "\n\n[تم اقتطاع النتيجة لحماية الذاكرة]"
    }

    companion object {
        const val ACTION_ID = "project-file-read"
        const val MODEL_TOOL_NAME = "read_project_file"
        const val ARG_SELECTION_ID = "selectionId"
        const val ARG_PATH = "path"
        const val MAX_RETURN_CHARS = 12_000
    }
}

object ProjectFileToolRequestPolicy {
    fun validate(arguments: Map<String, String>, activeSelectionId: String?): String? {
        if (arguments.keys != setOf(ProjectFileReadToolAdapter.ARG_SELECTION_ID, ProjectFileReadToolAdapter.ARG_PATH)) {
            return "معاملات طلب قراءة الملف غير معروفة."
        }
        if (activeSelectionId.isNullOrBlank()) return "اختر مجلد المشروع أولاً."
        if (arguments[ProjectFileReadToolAdapter.ARG_SELECTION_ID] != activeSelectionId) {
            return "طلب الملف لا يطابق اختيار المشروع الحالي."
        }
        val path = arguments[ProjectFileReadToolAdapter.ARG_PATH] ?: return "مسار الملف مفقود."
        val normalized = ProjectFilePolicy.normalizeRelativePath(path)
            ?: return "مسار الملف غير صالح."
        if (normalized != path) return "يجب أن يكون مسار الملف نسبياً ومنسقاً."
        val name = normalized.substringAfterLast('/')
        if (!ProjectFilePolicy.isEditableTextFile(name)) return "يسمح بقراءة الملفات النصية المدعومة فقط."
        return null
    }
}

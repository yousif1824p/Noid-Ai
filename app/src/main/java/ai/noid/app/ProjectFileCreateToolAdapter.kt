package ai.noid.app

import android.content.Context
import java.io.IOException

/** Creates an empty text file only inside the exact project tree selected by the user. */
class ProjectFileCreateToolAdapter(
    context: Context,
    private val selectedTree: SelectedProjectTreeSession,
    private val repository: ProjectFileRepository
) : NoidToolAdapter {
    private val resolver = context.applicationContext.contentResolver

    override val action = ToolAction(
        actionId = ACTION_ID,
        capability = ToolCapability.WRITE_FILES,
        risk = ToolRisk.SENSITIVE,
        description = "إنشاء ملف نصي فارغ داخل المشروع المختار بعد موافقة المستخدم"
    )

    override val modelToolName = MODEL_TOOL_NAME
    override val modelToolDescriptionJson = """
        {
          "name": "$MODEL_TOOL_NAME",
          "description": "أنشئ ملفاً نصياً فارغاً داخل مجلد المشروع الذي اختاره المستخدم. لا تستبدل الملفات الموجودة.",
          "parameters": {
            "type": "object",
            "properties": {
              "path": {
                "type": "string",
                "description": "مسار نسبي للملف الجديد مثل docs/NOTES.md"
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
            selectionArgument = ProjectFileReadToolAdapter.ARG_SELECTION_ID
        )

    override fun validate(request: ToolRequest): String? =
        ProjectFileCreateRequestPolicy.validate(
            request.arguments,
            selectedTree.current()?.selectionId
        )

    override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> {
        val selection = selectedTree.current() ?: return emptySet()
        if (request.arguments[ProjectFileReadToolAdapter.ARG_SELECTION_ID] != selection.selectionId) {
            return emptySet()
        }
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == selection.uri }
            ?: return emptySet()
        return if (grant.isWritePermission) setOf(ToolCapability.WRITE_FILES) else emptySet()
    }

    override suspend fun execute(request: ToolRequest): String {
        val selection = selectedTree.current()
            ?: throw IOException("لم يتم اختيار مجلد مشروع.")
        if (request.arguments[ProjectFileReadToolAdapter.ARG_SELECTION_ID] != selection.selectionId) {
            throw IOException("تغير مجلد المشروع بعد إنشاء الطلب؛ أعد المحاولة.")
        }
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == selection.uri }
        if (grant?.isWritePermission != true) {
            throw SecurityException("إذن الكتابة في مجلد المشروع غير متاح.")
        }
        val path = request.arguments[ARG_PATH] ?: throw IOException("مسار الملف مفقود.")
        repository.createTextFile(selection.uri, path, "")
        return "تم إنشاء الملف النصي الفارغ: $path"
    }

    companion object {
        const val ACTION_ID = "project-file-create-empty"
        const val MODEL_TOOL_NAME = "create_project_text_file"
        const val ARG_PATH = "path"
    }
}

object ProjectFileCreateRequestPolicy {
    fun validate(arguments: Map<String, String>, activeSelectionId: String?): String? {
        if (arguments.keys != setOf(ProjectFileReadToolAdapter.ARG_SELECTION_ID, ProjectFileCreateToolAdapter.ARG_PATH)) {
            return "معاملات طلب إنشاء الملف غير معروفة."
        }
        if (activeSelectionId.isNullOrBlank()) return "اختر مجلد المشروع أولاً."
        if (arguments[ProjectFileReadToolAdapter.ARG_SELECTION_ID] != activeSelectionId) {
            return "طلب إنشاء الملف لا يطابق اختيار المشروع الحالي."
        }
        val path = arguments[ProjectFileCreateToolAdapter.ARG_PATH]
            ?: return "مسار الملف مفقود."
        if (path != path.trim()) return "يجب أن يكون مسار الملف نسبياً ومنسقاً."
        val normalized = ProjectFilePolicy.normalizeRelativePath(path)
            ?: return "مسار الملف غير صالح."
        if (normalized != path) return "يجب أن يكون مسار الملف نسبياً ومنسقاً."
        val name = normalized.substringAfterLast('/')
        if (!ProjectFilePolicy.isEditableTextFile(name)) {
            return "يسمح بإنشاء الملفات النصية المدعومة فقط."
        }
        return null
    }
}

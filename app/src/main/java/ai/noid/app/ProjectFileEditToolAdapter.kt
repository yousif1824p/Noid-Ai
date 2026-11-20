package ai.noid.app

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Replaces a supported text file only after a full, consent-visible preview and approval. */
class ProjectFileEditToolAdapter(
    context: Context,
    private val selectedTree: SelectedProjectTreeSession,
    private val repository: ProjectFileRepository
) : NoidToolAdapter {
    private val resolver = context.applicationContext.contentResolver

    override val action = ToolAction(
        actionId = ACTION_ID,
        capability = ToolCapability.WRITE_FILES,
        risk = ToolRisk.SENSITIVE,
        description = "استبدال محتوى ملف نصي داخل المشروع المختار بعد عرض المحتوى والموافقة"
    )

    override val modelToolName = MODEL_TOOL_NAME
    override val modelToolDescriptionJson = """
        {
          "name": "$MODEL_TOOL_NAME",
          "description": "استبدل محتوى ملف نصي مدعوم داخل المشروع الذي اختاره المستخدم. اعرض NOID النص الكامل في نافذة الموافقة، وأنشئ نسخة احتياطية قبل الكتابة. الحد الأقصى 1200 حرف.",
          "parameters": {
            "type": "object",
            "properties": {
              "path": { "type": "string", "description": "المسار النسبي الحالي للملف داخل المشروع" },
              "content": { "type": "string", "description": "المحتوى الكامل الجديد، بحد أقصى 1200 حرف" }
            },
            "required": ["path", "content"],
            "additionalProperties": false
          }
        }
    """.trimIndent()

    override fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? =
        ProjectFileEditModelArgumentPolicy.bind(arguments, selectedTree.current()?.selectionId)

    override fun validate(request: ToolRequest): String? =
        ProjectFileEditRequestPolicy.validate(request.arguments, selectedTree.current()?.selectionId)

    override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> {
        val selection = selectedTree.current() ?: return emptySet()
        if (request.arguments[ARG_SELECTION_ID] != selection.selectionId) return emptySet()
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == selection.uri }
            ?: return emptySet()
        return if (grant.isWritePermission) setOf(ToolCapability.WRITE_FILES) else emptySet()
    }

    override suspend fun execute(request: ToolRequest): String {
        val selection = selectedTree.current()
            ?: throw IOException("لم يتم اختيار مجلد مشروع.")
        if (request.arguments[ARG_SELECTION_ID] != selection.selectionId) {
            throw IOException("تغير مجلد المشروع بعد إنشاء الطلب؛ أعد المحاولة.")
        }
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == selection.uri }
        if (grant?.isWritePermission != true) {
            throw SecurityException("إذن الكتابة في مجلد المشروع غير متاح.")
        }
        val path = request.arguments[ARG_PATH] ?: throw IOException("مسار الملف مفقود.")
        val content = request.arguments[ARG_CONTENT] ?: throw IOException("المحتوى الجديد مفقود.")
        val backup = withContext(Dispatchers.IO) {
            repository.writeText(selection.uri, path, content)
        }
        return "تم تحديث الملف: $path. النسخة السابقة محفوظة باسم: ${backup.backupFile.name}"
    }

    companion object {
        const val ACTION_ID = "project-file-edit-content"
        const val MODEL_TOOL_NAME = "edit_project_text_file"
        const val ARG_SELECTION_ID = "selectionId"
        const val ARG_PATH = "path"
        const val ARG_CONTENT = "content"
        const val MAX_CONTENT_CHARS = 1200
        const val MAX_CONTENT_BYTES = 4096
    }
}

object ProjectFileEditModelArgumentPolicy {
    fun bind(arguments: Map<String, Any?>, activeSelectionId: String?): Map<String, String>? {
        if (arguments.keys != setOf(
                ProjectFileEditToolAdapter.ARG_PATH,
                ProjectFileEditToolAdapter.ARG_CONTENT
            )
        ) return null
        val path = arguments[ProjectFileEditToolAdapter.ARG_PATH] as? String ?: return null
        val content = arguments[ProjectFileEditToolAdapter.ARG_CONTENT] as? String ?: return null
        if (activeSelectionId.isNullOrBlank()) return null
        return mapOf(
            ProjectFileEditToolAdapter.ARG_SELECTION_ID to activeSelectionId,
            ProjectFileEditToolAdapter.ARG_PATH to path,
            ProjectFileEditToolAdapter.ARG_CONTENT to content
        )
    }
}

object ProjectFileEditRequestPolicy {
    fun validate(arguments: Map<String, String>, activeSelectionId: String?): String? {
        if (arguments.keys != setOf(
                ProjectFileEditToolAdapter.ARG_SELECTION_ID,
                ProjectFileEditToolAdapter.ARG_PATH,
                ProjectFileEditToolAdapter.ARG_CONTENT
            )
        ) return "معاملات تعديل الملف غير معروفة."
        if (activeSelectionId.isNullOrBlank()) return "اختر مجلد المشروع أولاً."
        if (arguments[ProjectFileEditToolAdapter.ARG_SELECTION_ID] != activeSelectionId) {
            return "طلب تعديل الملف لا يطابق اختيار المشروع الحالي."
        }
        val path = arguments[ProjectFileEditToolAdapter.ARG_PATH]
            ?: return "مسار الملف مفقود."
        val normalized = ProjectFilePolicy.normalizeRelativePath(path)
            ?: return "مسار الملف غير صالح."
        if (normalized != path) return "يجب أن يكون مسار الملف نسبياً ومنسقاً."
        if (!ProjectFilePolicy.isEditableTextFile(normalized.substringAfterLast('/'))) {
            return "يسمح بتعديل الملفات النصية المدعومة فقط."
        }
        val content = arguments[ProjectFileEditToolAdapter.ARG_CONTENT]
            ?: return "المحتوى الجديد مفقود."
        if (content.length > ProjectFileEditToolAdapter.MAX_CONTENT_CHARS ||
            content.toByteArray(Charsets.UTF_8).size > ProjectFileEditToolAdapter.MAX_CONTENT_BYTES ||
            content.any {
                (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') ||
                    Character.getType(it) == Character.FORMAT.toInt()
            }
        ) return "المحتوى يتجاوز الحد أو يحتوي على محارف تحكم غير مسموحة."
        return null
    }
}

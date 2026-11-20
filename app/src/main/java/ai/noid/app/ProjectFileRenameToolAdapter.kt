package ai.noid.app

import android.content.Context
import java.io.IOException

/** Renames a supported text file only inside the exact SAF tree selected by the user. */
class ProjectFileRenameToolAdapter(
    context: Context,
    private val selectedTree: SelectedProjectTreeSession,
    private val repository: ProjectFileRepository
) : NoidToolAdapter {
    private val resolver = context.applicationContext.contentResolver

    override val action = ToolAction(
        actionId = ACTION_ID,
        capability = ToolCapability.WRITE_FILES,
        risk = ToolRisk.SENSITIVE,
        description = "إعادة تسمية ملف نصي داخل المشروع المختار بعد موافقة المستخدم"
    )

    override val modelToolName = MODEL_TOOL_NAME
    override val modelToolDescriptionJson = """
        {
          "name": "$MODEL_TOOL_NAME",
          "description": "أعد تسمية ملف نصي مدعوم داخل مجلد المشروع الذي اختاره المستخدم. لا تنقل الملف إلى مجلد آخر.",
          "parameters": {
            "type": "object",
            "properties": {
              "path": { "type": "string", "description": "المسار النسبي الحالي للملف داخل المشروع" },
              "newName": { "type": "string", "description": "اسم الملف الجديد فقط، مع امتداد نصي مدعوم" }
            },
            "required": ["path", "newName"],
            "additionalProperties": false
          }
        }
    """.trimIndent()

    override fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? =
        ProjectFileRenameModelArgumentPolicy.bind(
            arguments = arguments,
            activeSelectionId = selectedTree.current()?.selectionId
        )

    override fun validate(request: ToolRequest): String? =
        ProjectFileRenameRequestPolicy.validate(
            request.arguments,
            selectedTree.current()?.selectionId
        )

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
        val newName = request.arguments[ARG_NEW_NAME] ?: throw IOException("الاسم الجديد مفقود.")
        if (!repository.rename(selection.uri, path, newName)) {
            throw IOException("تعذر إعادة تسمية الملف.")
        }
        return "تمت إعادة تسمية الملف إلى: $newName"
    }

    companion object {
        const val ACTION_ID = "project-file-rename-model"
        const val MODEL_TOOL_NAME = "rename_project_text_file"
        const val ARG_SELECTION_ID = "selectionId"
        const val ARG_PATH = "path"
        const val ARG_NEW_NAME = "newName"
    }
}

object ProjectFileRenameModelArgumentPolicy {
    fun bind(
        arguments: Map<String, Any?>,
        activeSelectionId: String?
    ): Map<String, String>? {
        if (arguments.keys != setOf(
                ProjectFileRenameToolAdapter.ARG_PATH,
                ProjectFileRenameToolAdapter.ARG_NEW_NAME
            )
        ) return null
        val path = arguments[ProjectFileRenameToolAdapter.ARG_PATH] as? String ?: return null
        val newName = arguments[ProjectFileRenameToolAdapter.ARG_NEW_NAME] as? String ?: return null
        if (activeSelectionId.isNullOrBlank()) return null
        return mapOf(
            ProjectFileRenameToolAdapter.ARG_SELECTION_ID to activeSelectionId,
            ProjectFileRenameToolAdapter.ARG_PATH to path,
            ProjectFileRenameToolAdapter.ARG_NEW_NAME to newName
        )
    }
}

object ProjectFileRenameRequestPolicy {
    fun validate(arguments: Map<String, String>, activeSelectionId: String?): String? {
        if (arguments.keys != setOf(
                ProjectFileRenameToolAdapter.ARG_SELECTION_ID,
                ProjectFileRenameToolAdapter.ARG_PATH,
                ProjectFileRenameToolAdapter.ARG_NEW_NAME
            )
        ) return "معاملات طلب إعادة التسمية غير معروفة."
        if (activeSelectionId.isNullOrBlank()) return "اختر مجلد المشروع أولاً."
        if (arguments[ProjectFileRenameToolAdapter.ARG_SELECTION_ID] != activeSelectionId) {
            return "طلب إعادة التسمية لا يطابق اختيار المشروع الحالي."
        }
        val path = arguments[ProjectFileRenameToolAdapter.ARG_PATH]
            ?: return "مسار الملف مفقود."
        val normalized = ProjectFilePolicy.normalizeRelativePath(path)
            ?: return "مسار الملف غير صالح."
        if (normalized != path) return "يجب أن يكون مسار الملف نسبياً ومنسقاً."
        if (!ProjectFilePolicy.isEditableTextFile(normalized.substringAfterLast('/'))) {
            return "يسمح بإعادة تسمية الملفات النصية المدعومة فقط."
        }
        val newName = arguments[ProjectFileRenameToolAdapter.ARG_NEW_NAME]
            ?: return "الاسم الجديد مفقود."
        if (newName.isBlank() || newName != newName.trim() ||
            newName == "." || newName == ".." ||
            newName.contains('/') || newName.contains('\\') ||
            newName.length > MAX_NEW_NAME_CHARS ||
            ProjectFilePolicy.containsUnsafePathCharacters(newName)
        ) return "الاسم الجديد غير صالح."
        if (!ProjectFilePolicy.isEditableTextFile(newName)) {
            return "يسمح بأسماء الملفات النصية ذات الامتدادات المدعومة فقط."
        }
        if (normalized.substringAfterLast('/') == newName) {
            return "الاسم الجديد مطابق للاسم الحالي."
        }
        return null
    }

    private const val MAX_NEW_NAME_CHARS = 128
}

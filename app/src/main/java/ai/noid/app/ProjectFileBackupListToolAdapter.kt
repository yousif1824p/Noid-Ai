package ai.noid.app

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lists backup identifiers for one supported file in the user's selected project. */
class ProjectFileBackupListToolAdapter(
    context: Context,
    private val selectedTree: SelectedProjectTreeSession,
    private val repository: ProjectFileRepository
) : NoidToolAdapter {
    private val resolver = context.applicationContext.contentResolver

    override val action = ToolAction(
        actionId = ACTION_ID,
        capability = ToolCapability.READ_FILES,
        risk = ToolRisk.SENSITIVE,
        description = "عرض نسخ ملف المشروع الاحتياطية بعد موافقة المستخدم"
    )

    override val modelToolName = MODEL_TOOL_NAME
    override val modelToolDescriptionJson = """
        {
          "name": "$MODEL_TOOL_NAME",
          "description": "اعرض معرفات النسخ الاحتياطية لملف نصي واحد داخل المشروع المختار. لا تعرض محتوى النسخ.",
          "parameters": {
            "type": "object",
            "properties": {
              "path": { "type": "string", "description": "المسار النسبي للملف داخل المشروع" }
            },
            "required": ["path"],
            "additionalProperties": false
          }
        }
    """.trimIndent()

    override fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? =
        SelectedProjectModelArgumentPolicy.bindPath(
            arguments,
            selectedTree.current()?.selectionId,
            ARG_PATH,
            ARG_SELECTION_ID
        )

    override fun validate(request: ToolRequest): String? =
        ProjectFileBackupListRequestPolicy.validate(
            request.arguments,
            selectedTree.current()?.selectionId
        )

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
        val backups = withContext(Dispatchers.IO) { repository.listBackups(path).take(MAX_BACKUPS) }
        if (backups.isEmpty()) return "لا توجد نسخ احتياطية مسجلة لهذا الملف."
        return backups.mapIndexed { index, backup ->
            "${index + 1}. backupId=${backup.backupFile.name}; createdAtEpochMs=${backup.createdAt}"
        }.joinToString("\n")
    }

    companion object {
        const val ACTION_ID = "project-file-backup-list"
        const val MODEL_TOOL_NAME = "list_project_file_backups"
        const val ARG_SELECTION_ID = "selectionId"
        const val ARG_PATH = "path"
        const val MAX_BACKUPS = 20
    }
}

object ProjectFileBackupListRequestPolicy {
    fun validate(arguments: Map<String, String>, activeSelectionId: String?): String? {
        if (arguments.keys != setOf(
                ProjectFileBackupListToolAdapter.ARG_SELECTION_ID,
                ProjectFileBackupListToolAdapter.ARG_PATH
            )
        ) return "معاملات طلب عرض النسخ الاحتياطية غير معروفة."
        if (activeSelectionId.isNullOrBlank()) return "اختر مجلد المشروع أولاً."
        if (arguments[ProjectFileBackupListToolAdapter.ARG_SELECTION_ID] != activeSelectionId) {
            return "طلب النسخ الاحتياطية لا يطابق اختيار المشروع الحالي."
        }
        val path = arguments[ProjectFileBackupListToolAdapter.ARG_PATH]
            ?: return "مسار الملف مفقود."
        val normalized = ProjectFilePolicy.normalizeRelativePath(path)
            ?: return "مسار الملف غير صالح."
        if (normalized != path) return "يجب أن يكون مسار الملف نسبياً ومنسقاً."
        if (!ProjectFilePolicy.isEditableTextFile(normalized.substringAfterLast('/'))) {
            return "يسمح بعرض نسخ الملفات النصية المدعومة فقط."
        }
        return null
    }
}

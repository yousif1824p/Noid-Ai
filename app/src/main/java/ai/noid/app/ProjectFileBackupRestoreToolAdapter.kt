package ai.noid.app

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Restores one app-owned backup; repository creates a fresh rollback backup first. */
class ProjectFileBackupRestoreToolAdapter(
    context: Context,
    private val selectedTree: SelectedProjectTreeSession,
    private val repository: ProjectFileRepository
) : NoidToolAdapter {
    private val resolver = context.applicationContext.contentResolver

    override val action = ToolAction(
        actionId = ACTION_ID,
        capability = ToolCapability.WRITE_FILES,
        risk = ToolRisk.DESTRUCTIVE,
        description = "استعادة نسخة احتياطية لملف المشروع بعد موافقة صريحة"
    )

    override val modelToolName = MODEL_TOOL_NAME
    override val modelToolDescriptionJson = """
        {
          "name": "$MODEL_TOOL_NAME",
          "description": "استعد نسخة احتياطية محددة لملف نصي داخل المشروع المختار. ستُنشأ نسخة من المحتوى الحالي أولاً للرجوع عنه.",
          "parameters": {
            "type": "object",
            "properties": {
              "path": { "type": "string", "description": "المسار النسبي للملف داخل المشروع" },
              "backupId": { "type": "string", "description": "معرف backupId كما ظهر في قائمة النسخ الاحتياطية" }
            },
            "required": ["path", "backupId"],
            "additionalProperties": false
          }
        }
    """.trimIndent()

    override fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? =
        ProjectFileBackupRestoreModelArgumentPolicy.bind(arguments, selectedTree.current()?.selectionId)

    override fun validate(request: ToolRequest): String? {
        val selectionId = selectedTree.current()?.selectionId
        val structuralError = ProjectFileBackupRestoreRequestPolicy.validate(
            request.arguments,
            selectionId,
            availableBackupIds = emptySet(),
            requireAvailableBackup = false
        )
        if (structuralError != null) return structuralError
        val path = request.arguments[ARG_PATH] ?: return "مسار الملف مفقود."
        val backupId = request.arguments[ARG_BACKUP_ID] ?: return "معرف النسخة الاحتياطية مفقود."
        val availableIds = try {
            repository.listBackups(path).map { it.backupFile.name }.toSet()
        } catch (_: Exception) {
            return "تعذر التحقق من النسخة الاحتياطية."
        }
        return ProjectFileBackupRestoreRequestPolicy.validate(
            request.arguments,
            selectionId,
            availableIds
        )
    }

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
        val backupId = request.arguments[ARG_BACKUP_ID] ?: throw IOException("معرف النسخة الاحتياطية مفقود.")
        val backup = withContext(Dispatchers.IO) {
            repository.listBackups(path).firstOrNull { it.backupFile.name == backupId }
        } ?: throw IOException("النسخة الاحتياطية غير موجودة لهذا الملف.")
        val rollback = withContext(Dispatchers.IO) { repository.restoreBackup(selection.uri, backup) }
        return "تمت الاستعادة. نسخة الرجوع للمحتوى السابق: ${rollback.backupFile.name}"
    }

    companion object {
        const val ACTION_ID = "project-file-backup-restore"
        const val MODEL_TOOL_NAME = "restore_project_file_backup"
        const val ARG_SELECTION_ID = "selectionId"
        const val ARG_PATH = "path"
        const val ARG_BACKUP_ID = "backupId"
    }
}

object ProjectFileBackupRestoreModelArgumentPolicy {
    fun bind(arguments: Map<String, Any?>, activeSelectionId: String?): Map<String, String>? {
        if (arguments.keys != setOf(
                ProjectFileBackupRestoreToolAdapter.ARG_PATH,
                ProjectFileBackupRestoreToolAdapter.ARG_BACKUP_ID
            )
        ) return null
        val path = arguments[ProjectFileBackupRestoreToolAdapter.ARG_PATH] as? String ?: return null
        val backupId = arguments[ProjectFileBackupRestoreToolAdapter.ARG_BACKUP_ID] as? String ?: return null
        if (activeSelectionId.isNullOrBlank()) return null
        return mapOf(
            ProjectFileBackupRestoreToolAdapter.ARG_SELECTION_ID to activeSelectionId,
            ProjectFileBackupRestoreToolAdapter.ARG_PATH to path,
            ProjectFileBackupRestoreToolAdapter.ARG_BACKUP_ID to backupId
        )
    }
}

object ProjectFileBackupRestoreRequestPolicy {
    fun validate(
        arguments: Map<String, String>,
        activeSelectionId: String?,
        availableBackupIds: Set<String>,
        requireAvailableBackup: Boolean = true
    ): String? {
        if (arguments.keys != setOf(
                ProjectFileBackupRestoreToolAdapter.ARG_SELECTION_ID,
                ProjectFileBackupRestoreToolAdapter.ARG_PATH,
                ProjectFileBackupRestoreToolAdapter.ARG_BACKUP_ID
            )
        ) return "معاملات طلب الاستعادة غير معروفة."
        if (activeSelectionId.isNullOrBlank()) return "اختر مجلد المشروع أولاً."
        if (arguments[ProjectFileBackupRestoreToolAdapter.ARG_SELECTION_ID] != activeSelectionId) {
            return "طلب الاستعادة لا يطابق اختيار المشروع الحالي."
        }
        val path = arguments[ProjectFileBackupRestoreToolAdapter.ARG_PATH]
            ?: return "مسار الملف مفقود."
        val normalized = ProjectFilePolicy.normalizeRelativePath(path)
            ?: return "مسار الملف غير صالح."
        if (normalized != path) return "يجب أن يكون مسار الملف نسبياً ومنسقاً."
        if (!ProjectFilePolicy.isEditableTextFile(normalized.substringAfterLast('/'))) {
            return "يسمح باستعادة الملفات النصية المدعومة فقط."
        }
        val backupId = arguments[ProjectFileBackupRestoreToolAdapter.ARG_BACKUP_ID]
            ?: return "معرف النسخة الاحتياطية مفقود."
        if (!BACKUP_ID_REGEX.matches(backupId)) return "معرف النسخة الاحتياطية غير صالح."
        if (requireAvailableBackup && backupId !in availableBackupIds) {
            return "النسخة الاحتياطية غير موجودة لهذا الملف."
        }
        return null
    }

    private val BACKUP_ID_REGEX = Regex("noid-project-[A-Za-z0-9_-]+\\.bak")
}

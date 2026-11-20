package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectFileBackupRestoreRequestPolicyTest {
    private val selectionId = "active-selection"
    private val backupId = "noid-project-a1B2c3.bak"
    private fun request(path: String = "src/Main.kt", id: String = backupId) = mapOf(
        ProjectFileBackupRestoreToolAdapter.ARG_SELECTION_ID to selectionId,
        ProjectFileBackupRestoreToolAdapter.ARG_PATH to path,
        ProjectFileBackupRestoreToolAdapter.ARG_BACKUP_ID to id
    )

    @Test
    fun acceptsOnlyExistingBackupForSupportedRelativeFile() {
        assertNull(ProjectFileBackupRestoreRequestPolicy.validate(request(), selectionId, setOf(backupId)))
    }

    @Test
    fun rejectsMissingOrMismatchedSelection() {
        assertEquals(
            "اختر مجلد المشروع أولاً.",
            ProjectFileBackupRestoreRequestPolicy.validate(request(), null, setOf(backupId))
        )
        assertEquals(
            "طلب الاستعادة لا يطابق اختيار المشروع الحالي.",
            ProjectFileBackupRestoreRequestPolicy.validate(request(), "other", setOf(backupId))
        )
    }

    @Test
    fun rejectsUnknownArgumentsAndUnsafePaths() {
        assertEquals(
            "معاملات طلب الاستعادة غير معروفة.",
            ProjectFileBackupRestoreRequestPolicy.validate(
                request() + ("uri" to "content://forged"), selectionId, setOf(backupId)
            )
        )
        listOf("../secret.kt", "/absolute.kt", "src/../Main.kt", "src\\Main.kt").forEach { path ->
            org.junit.Assert.assertNotNull(
                ProjectFileBackupRestoreRequestPolicy.validate(request(path), selectionId, setOf(backupId))
            )
        }
    }

    @Test
    fun rejectsUnsupportedFileTypesAndMissingBackups() {
        org.junit.Assert.assertNotNull(
            ProjectFileBackupRestoreRequestPolicy.validate(
                request("build/classes.dex"), selectionId, setOf(backupId)
            )
        )
        assertEquals(
            "النسخة الاحتياطية غير موجودة لهذا الملف.",
            ProjectFileBackupRestoreRequestPolicy.validate(request(), selectionId, emptySet())
        )
    }

    @Test
    fun rejectsMalformedBackupIdentifiers() {
        listOf(
            "../outside.bak", "/tmp/backup.bak", "backup.txt",
            "noid-project-../escape.bak", "noid-project-a1.bak/extra"
        ).forEach { id ->
            assertEquals(
                "معرف النسخة الاحتياطية غير صالح.",
                ProjectFileBackupRestoreRequestPolicy.validate(request(id = id), selectionId, setOf(id))
            )
        }
    }

    @Test
    fun modelBindingInjectsSelectionAndRejectsForgedFields() {
        val bound = ProjectFileBackupRestoreModelArgumentPolicy.bind(
            mapOf("path" to "src/Main.kt", "backupId" to backupId),
            selectionId
        )
        assertEquals(selectionId, bound?.get("selectionId"))
        assertEquals(backupId, bound?.get("backupId"))
        assertEquals(null, ProjectFileBackupRestoreModelArgumentPolicy.bind(
            mapOf("path" to "src/Main.kt", "backupId" to backupId, "selectionId" to "forged"),
            selectionId
        ))
        assertEquals(null, ProjectFileBackupRestoreModelArgumentPolicy.bind(
            mapOf("path" to "src/Main.kt", "backupId" to 42),
            selectionId
        ))
    }
}

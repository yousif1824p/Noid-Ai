package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectFileBackupListRequestPolicyTest {
    private val selectionId = "active-selection"
    private fun request(path: String = "src/Main.kt") = mapOf(
        ProjectFileBackupListToolAdapter.ARG_SELECTION_ID to selectionId,
        ProjectFileBackupListToolAdapter.ARG_PATH to path
    )

    @Test
    fun acceptsSupportedRelativeTextFile() {
        assertNull(ProjectFileBackupListRequestPolicy.validate(request(), selectionId))
    }

    @Test
    fun rejectsMissingOrMismatchedSelection() {
        assertEquals("اختر مجلد المشروع أولاً.", ProjectFileBackupListRequestPolicy.validate(request(), null))
        assertEquals(
            "طلب النسخ الاحتياطية لا يطابق اختيار المشروع الحالي.",
            ProjectFileBackupListRequestPolicy.validate(request(), "other")
        )
    }

    @Test
    fun rejectsUnknownArgumentsAndUnsafePaths() {
        assertEquals(
            "معاملات طلب عرض النسخ الاحتياطية غير معروفة.",
            ProjectFileBackupListRequestPolicy.validate(request() + ("uri" to "content://forged"), selectionId)
        )
        listOf("../secret.kt", "/absolute.kt", "src/../Main.kt", "src\\Main.kt").forEach { path ->
            org.junit.Assert.assertNotNull(ProjectFileBackupListRequestPolicy.validate(request(path), selectionId))
        }
    }

    @Test
    fun rejectsUnsupportedFileTypes() {
        assertEquals(
            "يسمح بعرض نسخ الملفات النصية المدعومة فقط.",
            ProjectFileBackupListRequestPolicy.validate(request("build/classes.dex"), selectionId)
        )
    }
}

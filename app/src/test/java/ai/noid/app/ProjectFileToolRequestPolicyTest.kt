package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectFileToolRequestPolicyTest {
    private val selectionId = "selection-123"

    @Test
    fun acceptsOnlyTextFileInsideCurrentUserSelection() {
        assertNull(ProjectFileToolRequestPolicy.validate(
            mapOf("selectionId" to selectionId, "path" to "src/Main.kt"), selectionId
        ))
    }

    @Test
    fun rejectsMissingOrStaleSelection() {
        assertEquals("اختر مجلد المشروع أولاً.", ProjectFileToolRequestPolicy.validate(
            mapOf("selectionId" to selectionId, "path" to "src/Main.kt"), null
        ))
        assertEquals("طلب الملف لا يطابق اختيار المشروع الحالي.", ProjectFileToolRequestPolicy.validate(
            mapOf("selectionId" to "old-selection", "path" to "src/Main.kt"), selectionId
        ))
    }

    @Test
    fun rejectsTraversalAbsoluteAndUnsupportedFiles() {
        listOf("../secret.kt", "/etc/passwd", "assets/app.apk").forEach { path ->
            val result = ProjectFileToolRequestPolicy.validate(
                mapOf("selectionId" to selectionId, "path" to path), selectionId
            )
            org.junit.Assert.assertNotNull(result)
        }
    }

    @Test
    fun rejectsUnexpectedArguments() {
        val result = ProjectFileToolRequestPolicy.validate(
            mapOf("selectionId" to selectionId, "path" to "src/Main.kt", "uri" to "content://forged"),
            selectionId
        )
        assertEquals("معاملات طلب قراءة الملف غير معروفة.", result)
    }
}

package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectFileCreateRequestPolicyTest {
    private fun args(path: String, selectionId: String = "active") = mapOf(
        ProjectFileReadToolAdapter.ARG_SELECTION_ID to selectionId,
        ProjectFileCreateToolAdapter.ARG_PATH to path
    )

    @Test
    fun acceptsSupportedRelativeTextFilePath() {
        assertNull(ProjectFileCreateRequestPolicy.validate(args("docs/NOTES.md"), "active"))
    }

    @Test
    fun rejectsMissingOrStaleProjectSelection() {
        assertEquals(
            "اختر مجلد المشروع أولاً.",
            ProjectFileCreateRequestPolicy.validate(args("notes.txt"), null)
        )
        assertEquals(
            "طلب إنشاء الملف لا يطابق اختيار المشروع الحالي.",
            ProjectFileCreateRequestPolicy.validate(args("notes.txt", "old"), "active")
        )
    }

    @Test
    fun rejectsTraversalAbsoluteAndHiddenFormatPaths() {
        listOf("../notes.txt", "/notes.txt", "docs/\u202Etxt.txt", "docs/\u200Bnotes.txt").forEach { path ->
            assertEquals(
                "مسار الملف غير صالح.",
                ProjectFileCreateRequestPolicy.validate(args(path), "active")
            )
        }
    }

    @Test
    fun rejectsUnsupportedExtensionsAndUnknownArguments() {
        assertEquals(
            "يسمح بإنشاء الملفات النصية المدعومة فقط.",
            ProjectFileCreateRequestPolicy.validate(args("image.png"), "active")
        )
        assertEquals(
            "معاملات طلب إنشاء الملف غير معروفة.",
            ProjectFileCreateRequestPolicy.validate(args("notes.txt") + ("unexpected" to "value"), "active")
        )
    }

    @Test
    fun rejectsNonCanonicalRelativePaths() {
        assertEquals(
            "يجب أن يكون مسار الملف نسبياً ومنسقاً.",
            ProjectFileCreateRequestPolicy.validate(args(" docs/notes.txt "), "active")
        )
    }
}

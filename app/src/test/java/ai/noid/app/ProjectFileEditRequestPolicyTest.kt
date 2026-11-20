package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileEditRequestPolicyTest {
    private val selection = "app-owned-selection"

    private fun request(path: String = "src/Main.kt", content: String = "fun main() = Unit") =
        mapOf(
            ProjectFileEditToolAdapter.ARG_SELECTION_ID to selection,
            ProjectFileEditToolAdapter.ARG_PATH to path,
            ProjectFileEditToolAdapter.ARG_CONTENT to content
        )

    @Test
    fun acceptsSupportedRelativeTextFileAndMultilineContent() {
        assertNull(ProjectFileEditRequestPolicy.validate(
            request(content = "fun main() {\n    println(\"NOID\")\n}"),
            selection
        ))
    }

    @Test
    fun rejectsMissingOrChangedSelection() {
        assertEquals("اختر مجلد المشروع أولاً.",
            ProjectFileEditRequestPolicy.validate(request(), null))
        assertEquals("طلب تعديل الملف لا يطابق اختيار المشروع الحالي.",
            ProjectFileEditRequestPolicy.validate(request() + ("selectionId" to "stale"), selection))
    }

    @Test
    fun rejectsUnknownArgumentsAndForgedSelectionBinding() {
        assertEquals("معاملات تعديل الملف غير معروفة.",
            ProjectFileEditRequestPolicy.validate(request() + ("uri" to "content://forged"), selection))
        assertEquals(selection, ProjectFileEditModelArgumentPolicy.bind(
            mapOf("path" to "src/Main.kt", "content" to "new"),
            selection
        )?.get("selectionId"))
        assertEquals(null, ProjectFileEditModelArgumentPolicy.bind(
            mapOf("path" to "src/Main.kt", "content" to "new", "selectionId" to "forged"),
            selection
        ))
        assertEquals(null, ProjectFileEditModelArgumentPolicy.bind(
            mapOf("path" to "src/Main.kt", "content" to 42),
            selection
        ))
    }

    @Test
    fun rejectsTraversalAbsoluteAndUnsupportedFilePaths() {
        listOf("../secret.kt", "/secret.kt", "src/../Main.kt", "build/output.dex").forEach { path ->
            assertTrue(ProjectFileEditRequestPolicy.validate(request(path = path), selection) != null)
        }
    }

    @Test
    fun rejectsOversizedAndUnsafeContent() {
        assertTrue(ProjectFileEditRequestPolicy.validate(
            request(content = "x".repeat(ProjectFileEditToolAdapter.MAX_CONTENT_CHARS + 1)),
            selection
        ) != null)
        assertTrue(ProjectFileEditRequestPolicy.validate(request(content = "safe\u202Etext"), selection) != null)
        assertTrue(ProjectFileEditRequestPolicy.validate(request(content = "safe\u0000text"), selection) != null)
    }

    @Test
    fun permitsEmptyContentOnlyAsAnExplicitApprovedReplacement() {
        assertNull(ProjectFileEditRequestPolicy.validate(request(content = ""), selection))
    }
}

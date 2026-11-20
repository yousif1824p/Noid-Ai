package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectFileRenameRequestPolicyTest {
    private val selectionId = "app-owned-selection"
    private fun request(path: String = "src/MainActivity.kt", newName: String = "Assistant.kt") =
        mapOf(
            ProjectFileRenameToolAdapter.ARG_SELECTION_ID to selectionId,
            ProjectFileRenameToolAdapter.ARG_PATH to path,
            ProjectFileRenameToolAdapter.ARG_NEW_NAME to newName
        )

    @Test
    fun acceptsSupportedRelativeTextFileRename() {
        assertNull(ProjectFileRenameRequestPolicy.validate(request(), selectionId))
    }

    @Test
    fun rejectsMissingOrMismatchedSelection() {
        assertEquals(
            "اختر مجلد المشروع أولاً.",
            ProjectFileRenameRequestPolicy.validate(request(), null)
        )
        assertEquals(
            "طلب إعادة التسمية لا يطابق اختيار المشروع الحالي.",
            ProjectFileRenameRequestPolicy.validate(request(), "another-selection")
        )
    }

    @Test
    fun rejectsUnknownOrMissingArguments() {
        assertEquals(
            "معاملات طلب إعادة التسمية غير معروفة.",
            ProjectFileRenameRequestPolicy.validate(request() + ("uri" to "content://forged"), selectionId)
        )
        assertEquals(
            "معاملات طلب إعادة التسمية غير معروفة.",
            ProjectFileRenameRequestPolicy.validate(request() - ProjectFileRenameToolAdapter.ARG_NEW_NAME, selectionId)
        )
    }

    @Test
    fun rejectsTraversalAbsoluteAndNonNormalizedPaths() {
        listOf("../secret.kt", "/storage/secret.kt", "src/../Main.kt", "src\\MainActivity.kt").forEach { path ->
            org.junit.Assert.assertNotNull(
                ProjectFileRenameRequestPolicy.validate(request(path = path), selectionId)
            )
        }
    }

    @Test
    fun rejectsUnsupportedSourceFileTypes() {
        assertNotNullMessage(request(path = "build/classes.dex"), "يسمح بإعادة تسمية الملفات النصية المدعومة فقط.")
    }

    @Test
    fun rejectsUnsafeOrUnsupportedNewNames() {
        listOf(
            "../escape.kt", "folder/Name.kt", "folder\\Name.kt", ".", "..",
            "name.apk", " trailing.kt", "trailing.kt ", "bad\nname.kt",
            "bad\u202Ename.kt", "a".repeat(129) + ".kt"
        ).forEach { name ->
            org.junit.Assert.assertNotNull(
                ProjectFileRenameRequestPolicy.validate(request(newName = name), selectionId)
            )
        }
    }

    @Test
    fun rejectsUnchangedName() {
        assertEquals(
            "الاسم الجديد مطابق للاسم الحالي.",
            ProjectFileRenameRequestPolicy.validate(
                request(path = "src/Assistant.kt", newName = "Assistant.kt"),
                selectionId
            )
        )
    }

    @Test
    fun modelBindingAcceptsOnlyTwoStringArgumentsAndInjectsSelection() {
        val bound = ProjectFileRenameModelArgumentPolicy.bind(
            mapOf("path" to "src/Main.kt", "newName" to "Assistant.kt"),
            selectionId
        )
        assertEquals(selectionId, bound?.get("selectionId"))
        assertEquals("src/Main.kt", bound?.get("path"))
        assertEquals("Assistant.kt", bound?.get("newName"))
    }

    @Test
    fun modelCannotForgeSelectionOrSupplyNonStringArguments() {
        assertEquals(null, ProjectFileRenameModelArgumentPolicy.bind(
            mapOf("path" to "src/Main.kt", "newName" to "Assistant.kt", "selectionId" to "forged"),
            selectionId
        ))
        assertEquals(null, ProjectFileRenameModelArgumentPolicy.bind(
            mapOf("path" to "src/Main.kt", "newName" to 7),
            selectionId
        ))
        assertEquals(null, ProjectFileRenameModelArgumentPolicy.bind(
            mapOf("path" to "src/Main.kt", "newName" to "Assistant.kt"),
            " "
        ))
    }

    private fun assertNotNullMessage(arguments: Map<String, String>, expected: String) {
        assertEquals(expected, ProjectFileRenameRequestPolicy.validate(arguments, selectionId))
    }
}

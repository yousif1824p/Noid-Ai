package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFilePolicyTest {
    @Test fun normalizesSafeRelativePaths() {
        assertEquals("app/src/MainActivity.kt", ProjectFilePolicy.normalizeRelativePath("app\\src\\MainActivity.kt"))
    }

    @Test fun rejectsTraversalAndAbsolutePaths() {
        assertEquals(null, ProjectFilePolicy.normalizeRelativePath("../secret.txt"))
        assertEquals(null, ProjectFilePolicy.normalizeRelativePath("/storage/file.txt"))
        assertEquals(null, ProjectFilePolicy.normalizeRelativePath("app/../../secret"))
    }

    @Test fun rejectsExcessivelyLongPathsAndSegments() {
        assertEquals(null, ProjectFilePolicy.normalizeRelativePath("a".repeat(ProjectFilePolicy.MAX_RELATIVE_PATH_CHARS + 1)))
        assertEquals(null, ProjectFilePolicy.normalizeRelativePath("a".repeat(ProjectFilePolicy.MAX_PATH_SEGMENT_CHARS + 1)))
    }

    @Test fun rejectsPathsWithTooManySegments() {
        val path = List(ProjectFilePolicy.MAX_PATH_SEGMENTS + 1) { "a" }.joinToString("/")
        assertEquals(null, ProjectFilePolicy.normalizeRelativePath(path))
    }

    @Test fun acceptsPathsAtConfiguredBoundaries() {
        assertEquals("a".repeat(ProjectFilePolicy.MAX_PATH_SEGMENT_CHARS), ProjectFilePolicy.normalizeRelativePath("a".repeat(ProjectFilePolicy.MAX_PATH_SEGMENT_CHARS)))
        val path = List(ProjectFilePolicy.MAX_PATH_SEGMENTS) { "a" }.joinToString("/")
        assertEquals(path, ProjectFilePolicy.normalizeRelativePath(path))
    }

    @Test fun rejectsControlAndUnicodeFormatCharactersInPaths() {
        listOf(
            "src/\nMain.kt",
            "src/Main.kt\t",
            "\u007fsecret.txt",
            "src/\u0000Main.kt",
            "src/\u202Eevil.kt",
            "src/\u200Bhidden.kt"
        ).forEach { path ->
            assertEquals(null, ProjectFilePolicy.normalizeRelativePath(path))
        }
    }

    @Test fun permitsSourceAndConfigTextFilesOnly() {
        assertTrue(ProjectFilePolicy.isEditableTextFile("MainActivity.kt"))
        assertTrue(ProjectFilePolicy.isEditableTextFile("AndroidManifest.xml"))
        assertTrue(ProjectFilePolicy.isEditableTextFile("settings.gradle.kts"))
        assertFalse(ProjectFilePolicy.isEditableTextFile("classes.dex"))
        assertFalse(ProjectFilePolicy.isEditableTextFile("payload.apk"))
    }
}

package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalArgumentFormatterTest {
    @Test
    fun previewShowsArgumentNamesAndLengthsButNeverValues() {
        val secret = "private-file-content-and-token"
        val summary = ToolApprovalArgumentFormatter.summarize(
            mapOf("path" to secret, "content" to secret)
        )
        assertTrue(summary.contains("path"))
        assertTrue(summary.contains("content"))
        assertTrue(summary.contains("قيمة مخفية"))
        assertFalse(summary.contains(secret))
    }

    @Test
    fun projectFileReadApprovalShowsExactRelativePathButHidesSelectionToken() {
        val path = "src/main/AndroidManifest.xml"
        val selectionToken = "private-selection-token"
        val summary = ToolApprovalArgumentFormatter.summarize(
            actionId = ProjectFileReadToolAdapter.ACTION_ID,
            arguments = mapOf(
                ProjectFileReadToolAdapter.ARG_PATH to path,
                ProjectFileReadToolAdapter.ARG_SELECTION_ID to selectionToken
            )
        )
        assertTrue(summary.contains(path))
        assertFalse(summary.contains(selectionToken))
        assertTrue(summary.contains("لن يُعرض محتوى الملف قبل الموافقة"))
    }

    @Test
    fun createFileApprovalShowsExactPathAndStatesItWillBeEmpty() {
        val path = "docs/NOTES.md"
        val summary = ToolApprovalArgumentFormatter.summarize(
            actionId = ProjectFileCreateToolAdapter.ACTION_ID,
            arguments = mapOf(
                ProjectFileCreateToolAdapter.ARG_PATH to path,
                ProjectFileReadToolAdapter.ARG_SELECTION_ID to "private-selection-token"
            )
        )
        assertTrue(summary.contains(path))
        assertTrue(summary.contains("سيُنشأ الملف فارغاً"))
        assertFalse(summary.contains("private-selection-token"))
    }

    @Test
    fun renameApprovalShowsOldPathAndNewNameButHidesSelectionToken() {
        val path = "src/MainActivity.kt"
        val newName = "AssistantActivity.kt"
        val token = "private-selection-token"
        val summary = ToolApprovalArgumentFormatter.summarize(
            actionId = ProjectFileRenameToolAdapter.ACTION_ID,
            arguments = mapOf(
                ProjectFileRenameToolAdapter.ARG_PATH to path,
                ProjectFileRenameToolAdapter.ARG_NEW_NAME to newName,
                ProjectFileRenameToolAdapter.ARG_SELECTION_ID to token
            )
        )
        assertTrue(summary.contains(path))
        assertTrue(summary.contains(newName))
        assertTrue(summary.contains("ستتغير تسمية الملف فقط"))
        assertFalse(summary.contains(token))
    }

    @Test
    fun renameApprovalStripsControlAndUnicodeFormatCharacters() {
        val summary = ToolApprovalArgumentFormatter.summarize(
            actionId = ProjectFileRenameToolAdapter.ACTION_ID,
            arguments = mapOf(
                ProjectFileRenameToolAdapter.ARG_PATH to "src/\nMain.kt",
                ProjectFileRenameToolAdapter.ARG_NEW_NAME to "Evil\u202Ekt."
            )
        )
        assertFalse(summary.contains("\nMain.kt"))
        assertFalse(summary.contains("\u202E"))
    }

    @Test
    fun projectFileApprovalStripsControlCharactersAndBoundsDisplayedPath() {
        val path = "src/\nsecret/" + "a".repeat(300)
        val summary = ToolApprovalArgumentFormatter.summarize(
            actionId = ProjectFileReadToolAdapter.ACTION_ID,
            arguments = mapOf(ProjectFileReadToolAdapter.ARG_PATH to path)
        )
        assertFalse(summary.contains("\nsecret"))
        assertTrue(summary.contains("…"))
        assertTrue(summary.length < 400)
    }

    @Test
    fun backupListApprovalShowsPathButHidesSelectionToken() {
        val path = "src/MainActivity.kt"
        val token = "private-selection-token"
        val summary = ToolApprovalArgumentFormatter.summarize(
            actionId = ProjectFileBackupListToolAdapter.ACTION_ID,
            arguments = mapOf(
                ProjectFileBackupListToolAdapter.ARG_PATH to path,
                ProjectFileBackupListToolAdapter.ARG_SELECTION_ID to token
            )
        )
        assertTrue(summary.contains(path))
        assertTrue(summary.contains("سجل النسخ الاحتياطية"))
        assertFalse(summary.contains(token))
    }

    @Test
    fun backupRestoreApprovalNamesBackupAndExplainsRollback() {
        val path = "src/MainActivity.kt"
        val backupId = "noid-project-a1B2.bak"
        val token = "private-selection-token"
        val summary = ToolApprovalArgumentFormatter.summarize(
            actionId = ProjectFileBackupRestoreToolAdapter.ACTION_ID,
            arguments = mapOf(
                ProjectFileBackupRestoreToolAdapter.ARG_PATH to path,
                ProjectFileBackupRestoreToolAdapter.ARG_BACKUP_ID to backupId,
                ProjectFileBackupRestoreToolAdapter.ARG_SELECTION_ID to token
            )
        )
        assertTrue(summary.contains(path))
        assertTrue(summary.contains(backupId))
        assertTrue(summary.contains("سينشئ NOID نسخة من المحتوى الحالي"))
        assertFalse(summary.contains(token))
    }

    @Test
    fun editApprovalShowsFullProposedContentAndBackupNoticeButHidesSelectionToken() {
        val path = "src/MainActivity.kt"
        val content = "fun main() {\n    println(\"NOID\")\n}\t"
        val token = "private-selection-token"
        val summary = ToolApprovalArgumentFormatter.summarize(
            actionId = ProjectFileEditToolAdapter.ACTION_ID,
            arguments = mapOf(
                ProjectFileEditToolAdapter.ARG_PATH to path,
                ProjectFileEditToolAdapter.ARG_CONTENT to content,
                ProjectFileEditToolAdapter.ARG_SELECTION_ID to token
            )
        )
        assertTrue(summary.contains(path))
        assertTrue(summary.contains("المحتوى الكامل المقترح"))
        assertTrue(summary.contains("println(\"NOID\")"))
        assertTrue(summary.contains("↵"))
        assertTrue(summary.contains("⇥"))
        assertTrue(summary.contains("ستُحفظ نسخة احتياطية"))
        assertFalse(summary.contains(token))
    }

    @Test
    fun editApprovalBoundsPathAndStripsUnsafeVisiblePathCharacters() {
        val path = "src/\nsecret/" + "a".repeat(300)
        val summary = ToolApprovalArgumentFormatter.summarize(
            actionId = ProjectFileEditToolAdapter.ACTION_ID,
            arguments = mapOf(
                ProjectFileEditToolAdapter.ARG_PATH to path,
                ProjectFileEditToolAdapter.ARG_CONTENT to "safe"
            )
        )
        assertFalse(summary.contains("\nsecret"))
        assertTrue(summary.contains("…"))
        assertTrue(summary.length < 1800)
    }

    @Test
    fun previewLimitsVisibleArguments() {
        val summary = ToolApprovalArgumentFormatter.summarize(
            (1..10).associate { "arg$it" to "value$it" }
        )
        assertTrue(summary.contains("ومعاملات أخرى: 2"))
        assertFalse(summary.contains("value10"))
    }

    @Test
    fun emptyArgumentsHaveClearMessage() {
        assertEquals("لا توجد معاملات إضافية.", ToolApprovalArgumentFormatter.summarize(emptyMap()))
    }
}

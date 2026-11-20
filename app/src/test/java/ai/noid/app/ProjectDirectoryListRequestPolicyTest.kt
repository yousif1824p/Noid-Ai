package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectDirectoryListRequestPolicyTest {
    private val selectionId = "selection-123"

    @Test fun acceptsProjectRootAndNestedRelativeDirectory() {
        assertNull(ProjectDirectoryListRequestPolicy.validate(mapOf("selectionId" to selectionId, "path" to ""), selectionId))
        assertNull(ProjectDirectoryListRequestPolicy.validate(mapOf("selectionId" to selectionId, "path" to "app/src/main"), selectionId))
    }

    @Test fun rejectsMissingOrStaleSelection() {
        assertEquals("اختر مجلد المشروع أولاً.", ProjectDirectoryListRequestPolicy.validate(mapOf("selectionId" to selectionId, "path" to ""), null))
        assertEquals("طلب المجلد لا يطابق اختيار المشروع الحالي.", ProjectDirectoryListRequestPolicy.validate(mapOf("selectionId" to "stale", "path" to ""), selectionId))
    }

    @Test fun rejectsTraversalAbsoluteAndBackslashPaths() {
        listOf("../outside", "/etc", "app/../../outside", "app\\\\src").forEach { path ->
            org.junit.Assert.assertNotNull(ProjectDirectoryListRequestPolicy.validate(mapOf("selectionId" to selectionId, "path" to path), selectionId))
        }
    }

    @Test fun rejectsUnexpectedArgumentsAndControlCharacters() {
        assertEquals("معاملات طلب عرض المجلد غير معروفة.", ProjectDirectoryListRequestPolicy.validate(mapOf("selectionId" to selectionId, "path" to "", "uri" to "content://forged"), selectionId))
        org.junit.Assert.assertNotNull(ProjectDirectoryListRequestPolicy.validate(mapOf("selectionId" to selectionId, "path" to "app\nsecret"), selectionId))
    }

    @Test fun rejectsOversizedPaths() {
        org.junit.Assert.assertNotNull(ProjectDirectoryListRequestPolicy.validate(mapOf("selectionId" to selectionId, "path" to "a".repeat(513)), selectionId))
    }
}
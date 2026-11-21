package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class SelfDevelopmentProposalToolPolicyTest {
    private val selection = "selected-tree-1"
    private fun request(path: String = "src/Worker.kt", content: String = "class Worker") = mapOf(
        SelfDevelopmentProposalToolAdapter.ARG_SELECTION_ID to selection,
        SelfDevelopmentProposalToolAdapter.ARG_TITLE to "تحسين المعالجة",
        SelfDevelopmentProposalToolAdapter.ARG_RATIONALE to "إصلاح حالة حدية",
        SelfDevelopmentProposalToolAdapter.ARG_PATH to path,
        SelfDevelopmentProposalToolAdapter.ARG_PROPOSED_CONTENT to content
    )

    @Test fun acceptsBoundedProposalForSelectedProject() {
        assertNull(SelfDevelopmentProposalToolPolicy.validate(request(), selection))
    }

    @Test fun rejectsMissingOrStaleSelection() {
        assertEquals("اختر مجلد المشروع أولاً.", SelfDevelopmentProposalToolPolicy.validate(request(), null))
        assertEquals("طلب المقترح لا يطابق اختيار المشروع الحالي.",
            SelfDevelopmentProposalToolPolicy.validate(request(), "another-tree"))
    }

    @Test fun rejectsTraversalAbsoluteAndUnsupportedPaths() {
        listOf("../secret.kt", "/absolute.kt", "assets/model.bin").forEach {
            assertNotNull(SelfDevelopmentProposalToolPolicy.validate(request(path = it), selection))
        }
    }

    @Test fun rejectsOversizedAndControlCharacterContent() {
        assertNotNull(SelfDevelopmentProposalToolPolicy.validate(
            request(content = "x".repeat(SelfDevelopmentProposalToolAdapter.MAX_PROPOSED_CHARS + 1)), selection))
        assertNotNull(SelfDevelopmentProposalToolPolicy.validate(request(content = "bad\u0000text"), selection))
    }

    @Test fun rejectsUnexpectedArguments() {
        assertEquals("معاملات مقترح التطوير غير معروفة أو غير مكتملة.",
            SelfDevelopmentProposalToolPolicy.validate(request() + ("uri" to "content://untrusted"), selection))
    }
}

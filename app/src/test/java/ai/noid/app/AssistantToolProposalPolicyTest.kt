package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantToolProposalPolicyTest {
    private val registered = setOf("project.read-text")

    @Test
    fun acceptsOnlyRegisteredToolAndBoundedStringArguments() {
        val result = AssistantToolProposalPolicy.validate(
            AssistantToolProposal("project.read-text", mapOf("path" to "src/Main.kt"), "اقرأ الملف"),
            registered
        )
        assertTrue(result is AssistantToolProposalValidation.Accepted)
        val accepted = result as AssistantToolProposalValidation.Accepted
        assertEquals("src/Main.kt", accepted.request.arguments["path"])
        assertEquals("اقرأ الملف", accepted.explanation)
    }

    @Test
    fun rejectsUnknownAction() {
        val result = AssistantToolProposalPolicy.validate(
            AssistantToolProposal("shell.execute", emptyMap()), registered
        )
        assertEquals(
            AssistantToolProposalValidation.Rejected("الأداة المطلوبة غير مسجلة."),
            result
        )
    }

    @Test
    fun rejectsModelSuppliedPrivilegesUrisAndExecutionFields() {
        for (key in listOf("uri", "permissions", "capability", "approved", "auditId", "command", "code")) {
            val result = AssistantToolProposalPolicy.validate(
                AssistantToolProposal("project.read-text", mapOf(key to "value")), registered
            )
            assertTrue("Expected $key to be rejected", result is AssistantToolProposalValidation.Rejected)
        }
    }

    @Test
    fun rejectsOversizedArgumentsAndExplanation() {
        val tooMany = (1..(ToolRequest.MAX_ARGUMENTS + 1)).associate { "k$it" to "v" }
        assertTrue(
            AssistantToolProposalPolicy.validate(
                AssistantToolProposal("project.read-text", tooMany), registered
            ) is AssistantToolProposalValidation.Rejected
        )
        assertTrue(
            AssistantToolProposalPolicy.validate(
                AssistantToolProposal("project.read-text", emptyMap(), "x".repeat(301)), registered
            ) is AssistantToolProposalValidation.Rejected
        )
    }

    @Test
    fun rejectsControlCharactersInExplanation() {
        val result = AssistantToolProposalPolicy.validate(
            AssistantToolProposal("project.read-text", emptyMap(), "read\nnow"), registered
        )
        assertFalse(result is AssistantToolProposalValidation.Accepted)
    }
}

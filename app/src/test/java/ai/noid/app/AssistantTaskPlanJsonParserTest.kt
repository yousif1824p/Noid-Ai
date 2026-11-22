package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssistantTaskPlanJsonParserTest {
    @Test fun parsesBoundedPlanAndCreatesStableStepIds() {
        val plan = AssistantTaskPlanJsonParser.parse(
            """{"goal":"قراءة ملف المشروع","steps":[{"actionId":"project-file-read","arguments":{"path":"README.md"},"explanation":"قراءة الملف"}]}""",
            "test-plan"
        )
        assertEquals("test-plan", plan.planId)
        assertEquals("قراءة ملف المشروع", plan.goal)
        assertEquals("step-1", plan.steps.single().stepId)
        assertEquals("README.md", plan.steps.single().proposal.arguments["path"])
    }

    @Test fun rejectsUnknownRootFields() {
        assertThrows(IllegalArgumentException::class.java) {
            AssistantTaskPlanJsonParser.parse(
                """{"goal":"x","steps":[],"permissions":["WRITE_FILES"]}""",
                "test-plan"
            )
        }
    }

    @Test fun rejectsNonStringArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            AssistantTaskPlanJsonParser.parse(
                """{"goal":"x","steps":[{"actionId":"project-file-read","arguments":{"path":7},"explanation":"x"}]}""",
                "test-plan"
            )
        }
    }

    @Test fun rejectsTooManyStepsBeforePolicyExecution() {
        val steps = (1..9).joinToString(",") {
            """{"actionId":"project-file-read","arguments":{"path":"$it.txt"},"explanation":"read"}"""
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantTaskPlanJsonParser.parse(
                """{"goal":"x","steps":[$steps]}""",
                "test-plan"
            )
        }
    }

    @Test fun rejectsUnknownStepFields() {
        assertThrows(IllegalArgumentException::class.java) {
            AssistantTaskPlanJsonParser.parse(
                """{"goal":"x","steps":[{"actionId":"project-file-read","arguments":{},"explanation":"x","approved":true}]}""",
                "test-plan"
            )
        }
    }
}

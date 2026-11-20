package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolExecutionPolicyTest {
    private val policy = ToolExecutionPolicy()

    @Test fun deniesMissingCapability() {
        val action = ToolAction("read-1", ToolCapability.READ_SCREEN, ToolRisk.READ_ONLY, "قراءة الشاشة")
        assertEquals(ToolDecision.DENY, policy.evaluate(action, emptySet(), true).decision)
    }

    @Test fun requiresConfirmationForDestructiveAction() {
        val action = ToolAction("delete-1", ToolCapability.DELETE_DATA, ToolRisk.DESTRUCTIVE, "حذف ملف")
        assertEquals(ToolDecision.REQUIRE_USER_CONFIRMATION, policy.evaluate(action, setOf(ToolCapability.DELETE_DATA), false).decision)
    }

    @Test fun allowsSensitiveActionAfterGrantAndConfirmation() {
        val action = ToolAction("send-1", ToolCapability.SEND_MESSAGE, ToolRisk.SENSITIVE, "إرسال رسالة")
        assertEquals(ToolDecision.ALLOW, policy.evaluate(action, setOf(ToolCapability.SEND_MESSAGE), true).decision)
    }

    @Test fun deniesMalformedAction() {
        val action = ToolAction("", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "")
        assertEquals(ToolDecision.DENY, policy.evaluate(action, setOf(ToolCapability.READ_FILES), true).decision)
    }

    @Test
    fun rejectsControlCharactersAndOversizedAuditMetadata() {
        val invalidActions = listOf(
            ToolAction("read\nforged", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "قراءة"),
            ToolAction("read", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "قراءة\u202Eمزيفة"),
            ToolAction("x".repeat(129), ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "قراءة"),
            ToolAction("read", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "وصف".repeat(86)),
            ToolAction("read/forbidden", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "قراءة")
        )
        invalidActions.forEach { action ->
            assertEquals(
                ToolDecision.DENY,
                policy.evaluate(action, setOf(ToolCapability.READ_FILES), true).decision
            )
        }
    }

    @Test
    fun acceptsBoundedSafeAuditMetadata() {
        val action = ToolAction(
            "project.read-file:v1",
            ToolCapability.READ_FILES,
            ToolRisk.READ_ONLY,
            "قراءة ملف المشروع"
        )
        assertEquals(
            ToolDecision.ALLOW,
            policy.evaluate(action, setOf(ToolCapability.READ_FILES), false).decision
        )
    }

    @Test
    fun sensitiveAndDestructiveActionsAlwaysRequireConfirmation() {
        listOf(ToolRisk.SENSITIVE, ToolRisk.DESTRUCTIVE).forEach { risk ->
            val action = ToolAction("protected-${risk.name.lowercase()}", ToolCapability.WRITE_FILES, risk, "إجراء محمي")
            assertEquals(
                ToolDecision.REQUIRE_USER_CONFIRMATION,
                policy.evaluate(action, setOf(ToolCapability.WRITE_FILES), false).decision
            )
        }
    }

    @Test
    fun readOnlyAndReversibleActionsNeedNoExtraConfirmationAfterCapabilityGrant() {
        listOf(ToolRisk.READ_ONLY, ToolRisk.REVERSIBLE).forEach { risk ->
            val action = ToolAction("safe-${risk.name.lowercase()}", ToolCapability.READ_FILES, risk, "إجراء محدود")
            assertEquals(
                ToolDecision.ALLOW,
                policy.evaluate(action, setOf(ToolCapability.READ_FILES), false).decision
            )
        }
    }

    @Test
    fun anotherCapabilityCannotSubstituteForTheRequiredCapability() {
        val action = ToolAction("write-1", ToolCapability.WRITE_FILES, ToolRisk.REVERSIBLE, "كتابة ملف")
        assertEquals(
            ToolDecision.DENY,
            policy.evaluate(action, setOf(ToolCapability.READ_FILES), true).decision
        )
    }
    @Test
    fun everyCapabilityRequiresItsExactGrantAndCannotBeSubstituted() {
        ToolCapability.values().forEach { required ->
            val action = ToolAction(
                "capability-${required.name.lowercase()}",
                required,
                ToolRisk.READ_ONLY,
                "اختبار صلاحية محددة"
            )
            assertEquals(
                "Missing grant must deny ${required.name}",
                ToolDecision.DENY,
                policy.evaluate(action, emptySet(), true).decision
            )
            ToolCapability.values().filter { it != required }.forEach { unrelated ->
                assertEquals(
                    "Unrelated grant ${unrelated.name} must not satisfy ${required.name}",
                    ToolDecision.DENY,
                    policy.evaluate(action, setOf(unrelated), true).decision
                )
            }
            assertEquals(
                "Exact grant must allow ${required.name}",
                ToolDecision.ALLOW,
                policy.evaluate(action, setOf(required), false).decision
            )
        }
    }

}
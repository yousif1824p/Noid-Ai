package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AssessmentCheckAuthorizationPolicyTest {
    @Test
    fun staticChecksRequireAuthorizedTargetAndAreReadOnly() {
        val denied = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.PROJECT_SOURCE_STATIC_READ,
            AssessmentScope(targetAuthorized = false)
        )
        assertEquals(AssessmentDecision.DENY, denied.decision)

        val apkWithoutConsent = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.APK_MANIFEST_READ,
            AssessmentScope(targetAuthorized = false)
        )
        assertEquals(AssessmentDecision.DENY, apkWithoutConsent.decision)

        val allowed = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.APK_MANIFEST_READ,
            AssessmentScope(targetAuthorized = true)
        )
        assertEquals(AssessmentDecision.ALLOW, allowed.decision)
        assertEquals(AssessmentRisk.READ_ONLY_LOCAL, allowed.risk)
    }

    @Test
    fun dynamicTestRequiresIsolatedLabAndSeparateConfirmation() {
        val outsideLab = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.CONTROLLED_LOCAL_DYNAMIC_TEST,
            AssessmentScope(targetAuthorized = true)
        )
        assertEquals(AssessmentDecision.DENY, outsideLab.decision)

        val needsConfirmation = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.CONTROLLED_LOCAL_DYNAMIC_TEST,
            AssessmentScope(targetAuthorized = true, isolatedLab = true)
        )
        assertEquals(AssessmentDecision.REQUIRE_USER_CONFIRMATION, needsConfirmation.decision)

        val allowed = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.CONTROLLED_LOCAL_DYNAMIC_TEST,
            AssessmentScope(
                targetAuthorized = true,
                isolatedLab = true,
                highRiskActionConfirmed = true
            )
        )
        assertEquals(AssessmentDecision.ALLOW, allowed.decision)
    }

    @Test
    fun activeNetworkTestRequiresLabNetworkScopeAndConfirmation() {
        val outsideScope = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.ACTIVE_NETWORK_TEST,
            AssessmentScope(targetAuthorized = true, isolatedLab = true)
        )
        assertEquals(AssessmentDecision.DENY, outsideScope.decision)

        val needsConfirmation = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.ACTIVE_NETWORK_TEST,
            AssessmentScope(
                targetAuthorized = true,
                isolatedLab = true,
                networkExplicitlyInScope = true
            )
        )
        assertEquals(AssessmentDecision.REQUIRE_USER_CONFIRMATION, needsConfirmation.decision)

        val allowed = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.ACTIVE_NETWORK_TEST,
            AssessmentScope(
                targetAuthorized = true,
                isolatedLab = true,
                networkExplicitlyInScope = true,
                highRiskActionConfirmed = true
            )
        )
        assertEquals(AssessmentDecision.ALLOW, allowed.decision)
    }

    @Test
    fun targetModificationIsAlwaysDeniedByAssessmentScanner() {
        val result = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.TARGET_MODIFICATION,
            AssessmentScope(
                targetAuthorized = true,
                isolatedLab = true,
                highRiskActionConfirmed = true
            )
        )
        assertEquals(AssessmentDecision.DENY, result.decision)
        assertEquals(AssessmentRisk.MODIFIES_TARGET, result.risk)
    }

    @Test
    fun unauthorisedTargetCannotPassDynamicOrNetworkChecksEvenWithOtherFlags() {
        val scope = AssessmentScope(
            targetAuthorized = false,
            isolatedLab = true,
            networkExplicitlyInScope = true,
            highRiskActionConfirmed = true
        )
        assertEquals(
            AssessmentDecision.DENY,
            AssessmentCheckAuthorizationPolicy.evaluate(
                SecurityAssessmentCheck.CONTROLLED_LOCAL_DYNAMIC_TEST, scope
            ).decision
        )
        assertEquals(
            AssessmentDecision.DENY,
            AssessmentCheckAuthorizationPolicy.evaluate(
                SecurityAssessmentCheck.ACTIVE_NETWORK_TEST, scope
            ).decision
        )
    }

    @Test
    fun targetModificationRemainsDeniedEvenWhenEveryScopeFlagIsEnabled() {
        val result = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.TARGET_MODIFICATION,
            AssessmentScope(
                targetAuthorized = true,
                isolatedLab = true,
                networkExplicitlyInScope = true,
                highRiskActionConfirmed = true
            )
        )
        assertEquals(AssessmentDecision.DENY, result.decision)
        assertEquals(AssessmentRisk.MODIFIES_TARGET, result.risk)
    }
}

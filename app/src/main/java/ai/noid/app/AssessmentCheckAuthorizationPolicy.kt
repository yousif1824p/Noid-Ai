package ai.noid.app

enum class SecurityAssessmentCheck {
    APK_MANIFEST_READ,
    PROJECT_SOURCE_STATIC_READ,
    CONTROLLED_LOCAL_DYNAMIC_TEST,
    ACTIVE_NETWORK_TEST,
    TARGET_MODIFICATION
}

enum class AssessmentRisk {
    READ_ONLY_LOCAL,
    CONTROLLED_RUNTIME,
    ACTIVE_NETWORK,
    MODIFIES_TARGET
}

enum class AssessmentDecision {
    ALLOW,
    REQUIRE_USER_CONFIRMATION,
    DENY
}

data class AssessmentScope(
    val targetAuthorized: Boolean,
    val isolatedLab: Boolean = false,
    val networkExplicitlyInScope: Boolean = false,
    val highRiskActionConfirmed: Boolean = false
)

data class AssessmentAuthorization(
    val decision: AssessmentDecision,
    val risk: AssessmentRisk,
    val reason: String
)

/**
 * Per-check gate. It decides whether an assessment check may proceed;
 * it never performs the check or grants Android permissions.
 */
object AssessmentCheckAuthorizationPolicy {
    fun evaluate(
        check: SecurityAssessmentCheck,
        scope: AssessmentScope
    ): AssessmentAuthorization {
        if (!scope.targetAuthorized) {
            return AssessmentAuthorization(
                AssessmentDecision.DENY,
                riskFor(check),
                "يلزم تأكيد امتلاك الهدف أو وجود تصريح بفحصه."
            )
        }

        return when (check) {
            SecurityAssessmentCheck.APK_MANIFEST_READ,
            SecurityAssessmentCheck.PROJECT_SOURCE_STATIC_READ ->
                AssessmentAuthorization(
                    AssessmentDecision.ALLOW,
                    AssessmentRisk.READ_ONLY_LOCAL,
                    "فحص محلي ساكن للقراءة فقط ضمن النطاق المؤكد."
                )

            SecurityAssessmentCheck.CONTROLLED_LOCAL_DYNAMIC_TEST -> {
                if (!scope.isolatedLab) {
                    AssessmentAuthorization(
                        AssessmentDecision.DENY,
                        AssessmentRisk.CONTROLLED_RUNTIME,
                        "الاختبار الديناميكي مسموح فقط داخل مختبر معزول."
                    )
                } else if (!scope.highRiskActionConfirmed) {
                    AssessmentAuthorization(
                        AssessmentDecision.REQUIRE_USER_CONFIRMATION,
                        AssessmentRisk.CONTROLLED_RUNTIME,
                        "يتطلب الاختبار الديناميكي تأكيداً منفصلاً."
                    )
                } else {
                    AssessmentAuthorization(
                        AssessmentDecision.ALLOW,
                        AssessmentRisk.CONTROLLED_RUNTIME,
                        "النطاق مختبر معزول والتأكيد الصريح متوفر."
                    )
                }
            }

            SecurityAssessmentCheck.ACTIVE_NETWORK_TEST -> {
                if (!scope.isolatedLab || !scope.networkExplicitlyInScope) {
                    AssessmentAuthorization(
                        AssessmentDecision.DENY,
                        AssessmentRisk.ACTIVE_NETWORK,
                        "اختبار الشبكة النشط يتطلب مختبراً معزولاً ونطاق شبكة مصرحاً به صراحة."
                    )
                } else if (!scope.highRiskActionConfirmed) {
                    AssessmentAuthorization(
                        AssessmentDecision.REQUIRE_USER_CONFIRMATION,
                        AssessmentRisk.ACTIVE_NETWORK,
                        "يتطلب اختبار الشبكة النشط تأكيداً منفصلاً."
                    )
                } else {
                    AssessmentAuthorization(
                        AssessmentDecision.ALLOW,
                        AssessmentRisk.ACTIVE_NETWORK,
                        "مختبر معزول ونطاق شبكة مصرح وتأكيد صريح."
                    )
                }
            }

            SecurityAssessmentCheck.TARGET_MODIFICATION ->
                AssessmentAuthorization(
                    AssessmentDecision.DENY,
                    AssessmentRisk.MODIFIES_TARGET,
                    "تعديل الهدف محظور ضمن فاحص الأمان؛ استخدم مراجعة إصلاح منفصلة مع معاينة وتأكيد."
                )
        }
    }

    private fun riskFor(check: SecurityAssessmentCheck): AssessmentRisk = when (check) {
        SecurityAssessmentCheck.APK_MANIFEST_READ,
        SecurityAssessmentCheck.PROJECT_SOURCE_STATIC_READ -> AssessmentRisk.READ_ONLY_LOCAL
        SecurityAssessmentCheck.CONTROLLED_LOCAL_DYNAMIC_TEST -> AssessmentRisk.CONTROLLED_RUNTIME
        SecurityAssessmentCheck.ACTIVE_NETWORK_TEST -> AssessmentRisk.ACTIVE_NETWORK
        SecurityAssessmentCheck.TARGET_MODIFICATION -> AssessmentRisk.MODIFIES_TARGET
    }
}

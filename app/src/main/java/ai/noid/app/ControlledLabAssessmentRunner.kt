package ai.noid.app

/**
 * Deterministic, side-effect-free lab harness.
 *
 * This is a simulation fixture, not an Android APK/process/network executor.
 * It exercises the assessment contract and finding pipeline without touching
 * files, launching activities, or sending traffic. Every emitted result is
 * explicitly marked simulation-only so it cannot be mistaken for a real test.
 */
enum class ControlledLabProbeType {
    EXTERNAL_REDIRECT,
    PATH_TRAVERSAL
}

data class ControlledLabProbe(
    val id: String,
    val type: ControlledLabProbeType,
    val input: String
)

data class ControlledLabFinding(
    val probeId: String,
    val title: String,
    val evidence: String,
    val confidence: ApkFindingConfidence = ApkFindingConfidence.MEDIUM
) {
    /**
     * Derived rather than caller-supplied: no constructor or copy() call can
     * relabel a simulated finding as a real-world observation.
     */
    val simulationOnly: Boolean
        get() = true
}

data class ControlledLabRunResult(
    val executed: Boolean,
    val findings: List<ControlledLabFinding>,
    val rejectedReason: String? = null,
    val sideEffects: Int = 0
) {
    /**
     * Provenance is an invariant of this fixture, including rejected runs.
     */
    val simulationOnly: Boolean
        get() = true
}

object ControlledLabAssessmentRunner {
    const val SAMPLE_TARGET_ID = "noid.sample-vulnerable-app.simulation.v1"
    const val MAX_PROBES = 8
    const val MAX_INPUT_LENGTH = 256

    fun run(
        targetId: String,
        scope: AssessmentScope,
        probes: List<ControlledLabProbe>
    ): ControlledLabRunResult {
        val authorization = AssessmentCheckAuthorizationPolicy.evaluate(
            SecurityAssessmentCheck.CONTROLLED_LOCAL_DYNAMIC_TEST,
            scope
        )
        if (authorization.decision != AssessmentDecision.ALLOW) {
            return ControlledLabRunResult(
                executed = false,
                findings = emptyList(),
                rejectedReason = authorization.reason
            )
        }
        if (targetId != SAMPLE_TARGET_ID) {
            return ControlledLabRunResult(
                executed = false,
                findings = emptyList(),
                rejectedReason = "الهدف ليس تطبيق المختبر التجريبي المسجل."
            )
        }
        if (probes.isEmpty() || probes.size > MAX_PROBES ||
            probes.any { it.id.isBlank() || it.id.length > 64 ||
                it.input.isBlank() || it.input.length > MAX_INPUT_LENGTH } ||
            probes.map { it.id }.distinct().size != probes.size
        ) {
            return ControlledLabRunResult(
                executed = false,
                findings = emptyList(),
                rejectedReason = "قائمة الاختبارات فارغة أو تتجاوز الحدود أو تحتوي قيماً غير صالحة."
            )
        }

        val findings = probes.mapNotNull { probe ->
            when (probe.type) {
                ControlledLabProbeType.EXTERNAL_REDIRECT ->
                    if (probe.input.startsWith("https://", ignoreCase = true)) {
                        ControlledLabFinding(
                            probe.id,
                            "المختبر يقبل وجهة إعادة توجيه خارجية دون قائمة سماح",
                            "قبلت محاكاة التطبيق الوجهة الخارجية؛ لم يُرسل أي طلب شبكة."
                        )
                    } else null
                ControlledLabProbeType.PATH_TRAVERSAL ->
                    if (probe.input.split('/', '\\').any { it == ".." }) {
                        ControlledLabFinding(
                            probe.id,
                            "المختبر لا يرفض مكوّن مسار صاعداً",
                            "رُصد مكوّن .. في مدخل المسار؛ لم تتم قراءة أي ملف."
                        )
                    } else null
            }
        }
        return ControlledLabRunResult(
            executed = true,
            findings = findings,
            sideEffects = 0
        )
    }
}

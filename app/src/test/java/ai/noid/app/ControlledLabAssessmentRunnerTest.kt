package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledLabAssessmentRunnerTest {
    private val authorizedLab = AssessmentScope(
        targetAuthorized = true,
        isolatedLab = true,
        highRiskActionConfirmed = true
    )

    @Test fun refusesWhenAuthorizationOrIsolationIsMissing() {
        val noConsent = ControlledLabAssessmentRunner.run(
            ControlledLabAssessmentRunner.SAMPLE_TARGET_ID,
            AssessmentScope(targetAuthorized = false, isolatedLab = true, highRiskActionConfirmed = true),
            listOf(redirectProbe())
        )
        val noIsolation = ControlledLabAssessmentRunner.run(
            ControlledLabAssessmentRunner.SAMPLE_TARGET_ID,
            AssessmentScope(targetAuthorized = true, highRiskActionConfirmed = true),
            listOf(redirectProbe())
        )
        assertFalse(noConsent.executed)
        assertFalse(noIsolation.executed)
        assertTrue(noConsent.findings.isEmpty())
        assertTrue(noIsolation.findings.isEmpty())
        assertTrue(noConsent.simulationOnly)
        assertTrue(noIsolation.simulationOnly)
    }

    @Test fun rejectsAnyTargetOtherThanTheRegisteredSampleFixture() {
        val result = ControlledLabAssessmentRunner.run(
            "com.example.unrelated",
            authorizedLab,
            listOf(redirectProbe())
        )
        assertFalse(result.executed)
        assertTrue(result.simulationOnly)
        assertTrue(result.rejectedReason!!.contains("المختبر"))
    }

    @Test fun reportsOnlyTheTwoKnownSimulatedWeakBehaviorsWithoutSideEffects() {
        val result = ControlledLabAssessmentRunner.run(
            ControlledLabAssessmentRunner.SAMPLE_TARGET_ID,
            authorizedLab,
            listOf(
                redirectProbe(),
                ControlledLabProbe("path", ControlledLabProbeType.PATH_TRAVERSAL, "../private/demo.txt")
            )
        )
        assertTrue(result.executed)
        assertTrue(result.simulationOnly)
        assertEquals(2, result.findings.size)
        assertEquals(0, result.sideEffects)
        assertTrue(result.findings.all { it.simulationOnly })
        assertTrue(result.findings.any { it.probeId == "redirect" })
        assertTrue(result.findings.any { it.probeId == "path" })
    }

    @Test fun simulationProvenanceCannotBeChangedThroughDataClassCopy() {
        val result = ControlledLabAssessmentRunner.run(
            ControlledLabAssessmentRunner.SAMPLE_TARGET_ID,
            authorizedLab,
            listOf(redirectProbe())
        )
        val copiedResult = result.copy(executed = false)
        val copiedFinding = result.findings.single().copy(title = "Edited display title")

        assertTrue(result.simulationOnly)
        assertTrue(copiedResult.simulationOnly)
        assertTrue(copiedFinding.simulationOnly)
    }

    @Test fun rejectsOversizedDuplicateAndEmptyProbeLists() {
        val oversized = ControlledLabProbe(
            "large", ControlledLabProbeType.EXTERNAL_REDIRECT,
            "x".repeat(ControlledLabAssessmentRunner.MAX_INPUT_LENGTH + 1)
        )
        val duplicate = redirectProbe().let { listOf(it, it) }
        listOf(emptyList(), listOf(oversized), duplicate).forEach { probes ->
            val result = ControlledLabAssessmentRunner.run(
                ControlledLabAssessmentRunner.SAMPLE_TARGET_ID, authorizedLab, probes
            )
            assertFalse(result.executed)
            assertTrue(result.simulationOnly)
            assertTrue(result.findings.isEmpty())
        }
    }

    @Test fun safeInputsProduceNoFindings() {
        val result = ControlledLabAssessmentRunner.run(
            ControlledLabAssessmentRunner.SAMPLE_TARGET_ID,
            authorizedLab,
            listOf(
                ControlledLabProbe("redirect", ControlledLabProbeType.EXTERNAL_REDIRECT, "internal://home"),
                ControlledLabProbe("path", ControlledLabProbeType.PATH_TRAVERSAL, "images/icon.png")
            )
        )
        assertTrue(result.executed)
        assertTrue(result.simulationOnly)
        assertTrue(result.findings.isEmpty())
        assertEquals(0, result.sideEffects)
    }

    private fun redirectProbe() = ControlledLabProbe(
        "redirect", ControlledLabProbeType.EXTERNAL_REDIRECT, "https://outside.invalid/"
    )
}

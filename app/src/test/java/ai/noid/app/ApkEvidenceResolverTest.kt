package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkEvidenceResolverTest {
    @Test fun emptyEvidenceIsInconclusive() {
        assertEquals(
            ApkAssessmentStatus.INSUFFICIENT_EVIDENCE,
            ApkEvidenceResolver.assess(emptyList()).status
        )
    }

    @Test fun negativeEvidenceIsLimitedToItsCheckScope() {
        val result = ApkEvidenceResolver.assess(listOf(
            ApkEvidenceRecord("exported.activity", ApkEvidenceOutcome.NOT_FLAGGED,
                ApkEvidenceSource.DETERMINISTIC_RULE, "No exported activity in this check.")
        ))
        assertEquals(ApkAssessmentStatus.NO_FLAGS_WITHIN_SCOPE, result.status)
    }

    @Test fun flaggedEvidenceProducesFinding() {
        val result = ApkEvidenceResolver.assess(listOf(
            ApkEvidenceRecord("debuggable", ApkEvidenceOutcome.FLAGGED,
                ApkEvidenceSource.DETERMINISTIC_RULE, "debuggable=true")
        ))
        assertEquals(ApkAssessmentStatus.FINDINGS_PRESENT, result.status)
        assertEquals(1, result.findings.size)
    }

    @Test fun contradictoryEvidenceIsNotSilentlyResolved() {
        val result = ApkEvidenceResolver.assess(listOf(
            ApkEvidenceRecord("network", ApkEvidenceOutcome.FLAGGED,
                ApkEvidenceSource.DETERMINISTIC_RULE, "cleartext enabled"),
            ApkEvidenceRecord("network", ApkEvidenceOutcome.NOT_FLAGGED,
                ApkEvidenceSource.DYNAMIC_OBSERVATION, "observed HTTPS only")
        ))
        assertEquals(ApkAssessmentStatus.CONFLICTING_EVIDENCE, result.status)
        assertEquals("network", result.conflicts.single().checkId)
    }

    @Test fun aiAdvisoryCannotChangeAnInconclusiveResult() {
        val result = ApkEvidenceResolver.assess(listOf(
            ApkEvidenceRecord("heuristic", ApkEvidenceOutcome.ADVISORY,
                ApkEvidenceSource.AI_ADVISORY, "Possible concern; validate manually.")
        ))
        assertEquals(ApkAssessmentStatus.INSUFFICIENT_EVIDENCE, result.status)
        assertTrue(result.authoritativeEvidence.isEmpty())
        assertEquals(1, result.aiAdvisories.size)
    }

    @Test fun aiAdviceCannotBeSubmittedAsAuthoritativeEvidence() {
        try {
            ApkEvidenceRecord("check", ApkEvidenceOutcome.FLAGGED,
                ApkEvidenceSource.AI_ADVISORY, "model claim")
            throw AssertionError("Expected invalid AI evidence to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun manifestFindingsFeedTheResolverWithoutTreatingInfoAsSecurityEvidence() {
        val report = ApkManifestAnalyzer.analyze(
            ApkManifestSnapshot("example.app", null, false, false, emptyList(), emptyList())
        )
        val result = ApkEvidenceResolver.fromManifestFindings(report.findings)
        assertEquals(ApkAssessmentStatus.INSUFFICIENT_EVIDENCE, result.status)
    }
    @Test fun manifestPermissionInventoryIsNotMisclassifiedAsSecurityEvidence() {
        val report = ApkManifestAnalyzer.analyze(
            ApkManifestSnapshot("example.app", null, false, false, listOf("CAMERA"), emptyList())
        )
        assertEquals(ApkAssessmentStatus.INSUFFICIENT_EVIDENCE, report.assessment.status)
        assertTrue(report.assessment.authoritativeEvidence.isEmpty())
    }

    @Test fun exportedReportExplainsInconclusiveAssessment() {
        val report = ApkManifestAnalyzer.analyze(
            ApkManifestSnapshot("example.app", null, false, false, emptyList(), emptyList())
        )
        val text = ApkReportFormatter.format(report)
        assertTrue(text.contains("Assessment: INSUFFICIENT_EVIDENCE"))
        assertTrue(text.contains("no findings are not proof of security"))
    }

}

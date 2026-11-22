from __future__ import annotations

import unittest

from noid_engine.security_evidence import (
    AssessmentStatus,
    EvidenceOutcome,
    EvidenceRecord,
    EvidenceSource,
    assess_evidence,
)


class SecurityEvidenceAssessmentTests(unittest.TestCase):
    def record(self, outcome, source=EvidenceSource.DETERMINISTIC_RULE, check="manifest.exported"):
        return EvidenceRecord(
            check_id=check,
            outcome=outcome,
            source=source,
            detail=f"Observed {outcome.value}",
        )

    def test_empty_evidence_is_inconclusive_not_safe(self):
        result = assess_evidence([])
        self.assertEqual(result.status, AssessmentStatus.INSUFFICIENT_EVIDENCE)
        self.assertEqual(result.findings, ())

    def test_clean_limited_scope_is_not_a_safe_verdict(self):
        result = assess_evidence([
            self.record(EvidenceOutcome.NOT_FLAGGED)
        ])
        self.assertEqual(result.status, AssessmentStatus.NO_FLAGS_WITHIN_SCOPE)
        self.assertNotEqual(result.status.value, "safe")

    def test_positive_deterministic_evidence_is_preserved(self):
        evidence = self.record(EvidenceOutcome.FLAGGED)
        result = assess_evidence([evidence])
        self.assertEqual(result.status, AssessmentStatus.FINDINGS_PRESENT)
        self.assertEqual(result.findings, (evidence,))

    def test_contradictory_sources_are_reported_not_silently_resolved(self):
        flagged = self.record(
            EvidenceOutcome.FLAGGED, EvidenceSource.DETERMINISTIC_RULE
        )
        clear = self.record(
            EvidenceOutcome.NOT_FLAGGED, EvidenceSource.DYNAMIC_OBSERVATION
        )
        result = assess_evidence([flagged, clear])
        self.assertEqual(result.status, AssessmentStatus.CONFLICTING_EVIDENCE)
        self.assertEqual(len(result.conflicts), 1)
        self.assertEqual(result.conflicts[0].check_id, flagged.check_id)

    def test_ai_advisory_is_separate_and_cannot_change_verdict(self):
        base = assess_evidence([self.record(EvidenceOutcome.NOT_FLAGGED)])
        with_advice = assess_evidence([
            self.record(EvidenceOutcome.NOT_FLAGGED),
            self.record(EvidenceOutcome.ADVISORY, EvidenceSource.AI_ADVISORY, "model.note"),
        ])
        self.assertEqual(with_advice.status, base.status)
        self.assertEqual(len(with_advice.authoritative_evidence), 1)
        self.assertEqual(len(with_advice.ai_advisories), 1)
        self.assertEqual(with_advice.findings, ())

    def test_ai_advisory_alone_cannot_establish_a_security_result(self):
        result = assess_evidence([
            self.record(EvidenceOutcome.ADVISORY, EvidenceSource.AI_ADVISORY, "model.note")
        ])
        self.assertEqual(result.status, AssessmentStatus.INSUFFICIENT_EVIDENCE)
        self.assertEqual(len(result.ai_advisories), 1)

    def test_invalid_ai_evidence_cannot_be_marked_authoritative(self):
        with self.assertRaises(ValueError):
            self.record(EvidenceOutcome.FLAGGED, EvidenceSource.AI_ADVISORY)

    def test_evidence_records_are_immutable(self):
        record = self.record(EvidenceOutcome.FLAGGED)
        with self.assertRaises((AttributeError, TypeError)):
            record.detail = "changed"


if __name__ == "__main__":
    unittest.main()

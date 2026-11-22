"""Evidence aggregation that never turns missing evidence into a safe verdict."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from collections import defaultdict
from collections.abc import Iterable


class EvidenceSource(str, Enum):
    ANDROID_MANIFEST_METADATA = "android_manifest_metadata"
    DETERMINISTIC_RULE = "deterministic_rule"
    DYNAMIC_OBSERVATION = "dynamic_observation"
    AI_ADVISORY = "ai_advisory"


class EvidenceOutcome(str, Enum):
    FLAGGED = "flagged"
    NOT_FLAGGED = "not_flagged"
    ADVISORY = "advisory"


class AssessmentStatus(str, Enum):
    INSUFFICIENT_EVIDENCE = "insufficient_evidence"
    NO_FLAGS_WITHIN_SCOPE = "no_flags_within_scope"
    FINDINGS_PRESENT = "findings_present"
    CONFLICTING_EVIDENCE = "conflicting_evidence"


_AUTHORITATIVE_SOURCES = frozenset({
    EvidenceSource.ANDROID_MANIFEST_METADATA,
    EvidenceSource.DETERMINISTIC_RULE,
    EvidenceSource.DYNAMIC_OBSERVATION,
})


@dataclass(frozen=True, slots=True)
class EvidenceRecord:
    check_id: str
    outcome: EvidenceOutcome
    source: EvidenceSource
    detail: str
    confidence: str = "medium"

    def __post_init__(self) -> None:
        if not self.check_id.strip():
            raise ValueError("check_id must not be empty")
        if not self.detail.strip():
            raise ValueError("detail must not be empty")
        if self.confidence not in {"low", "medium", "high"}:
            raise ValueError("confidence must be low, medium, or high")
        if self.source is EvidenceSource.AI_ADVISORY:
            if self.outcome is not EvidenceOutcome.ADVISORY:
                raise ValueError("AI advice must use the advisory outcome")
        elif self.outcome is EvidenceOutcome.ADVISORY:
            raise ValueError("Only AI advisory records may use advisory outcome")


@dataclass(frozen=True, slots=True)
class EvidenceConflict:
    check_id: str
    flagged: tuple[EvidenceRecord, ...]
    not_flagged: tuple[EvidenceRecord, ...]


@dataclass(frozen=True, slots=True)
class SecurityAssessment:
    status: AssessmentStatus
    authoritative_evidence: tuple[EvidenceRecord, ...]
    findings: tuple[EvidenceRecord, ...]
    conflicts: tuple[EvidenceConflict, ...]
    ai_advisories: tuple[EvidenceRecord, ...]


def assess_evidence(records: Iterable[EvidenceRecord]) -> SecurityAssessment:
    """Aggregate evidence while keeping AI advice outside the authoritative verdict.

    An empty evidence set is inconclusive. A clean limited-scope result is never
    labelled safe. Contradictory deterministic observations are surfaced rather
    than silently resolved by source order or confidence.
    """
    authoritative: list[EvidenceRecord] = []
    advisories: list[EvidenceRecord] = []
    by_check: dict[str, list[EvidenceRecord]] = defaultdict(list)

    for record in records:
        if record.source is EvidenceSource.AI_ADVISORY:
            advisories.append(record)
            continue
        if record.source not in _AUTHORITATIVE_SOURCES:
            continue
        authoritative.append(record)
        by_check[record.check_id].append(record)

    conflicts: list[EvidenceConflict] = []
    for check_id, group in sorted(by_check.items()):
        flagged = tuple(item for item in group if item.outcome is EvidenceOutcome.FLAGGED)
        not_flagged = tuple(
            item for item in group if item.outcome is EvidenceOutcome.NOT_FLAGGED
        )
        if flagged and not_flagged:
            conflicts.append(EvidenceConflict(check_id, flagged, not_flagged))

    findings = tuple(
        item for item in authoritative if item.outcome is EvidenceOutcome.FLAGGED
    )
    if conflicts:
        status = AssessmentStatus.CONFLICTING_EVIDENCE
    elif findings:
        status = AssessmentStatus.FINDINGS_PRESENT
    elif authoritative:
        status = AssessmentStatus.NO_FLAGS_WITHIN_SCOPE
    else:
        status = AssessmentStatus.INSUFFICIENT_EVIDENCE

    return SecurityAssessment(
        status=status,
        authoritative_evidence=tuple(authoritative),
        findings=findings,
        conflicts=tuple(conflicts),
        ai_advisories=tuple(advisories),
    )

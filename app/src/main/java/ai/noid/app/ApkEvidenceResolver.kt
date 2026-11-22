package ai.noid.app

enum class ApkEvidenceOutcome { FLAGGED, NOT_FLAGGED, ADVISORY }
enum class ApkEvidenceSource {
    ANDROID_MANIFEST_METADATA,
    DETERMINISTIC_RULE,
    DYNAMIC_OBSERVATION,
    AI_ADVISORY
}
enum class ApkAssessmentStatus {
    INSUFFICIENT_EVIDENCE,
    NO_FLAGS_WITHIN_SCOPE,
    FINDINGS_PRESENT,
    CONFLICTING_EVIDENCE
}

data class ApkEvidenceRecord(
    val checkId: String,
    val outcome: ApkEvidenceOutcome,
    val source: ApkEvidenceSource,
    val detail: String
) {
    init {
        require(checkId.isNotBlank()) { "checkId must not be blank" }
        require(detail.isNotBlank()) { "detail must not be blank" }
        require(
            (source == ApkEvidenceSource.AI_ADVISORY) ==
                (outcome == ApkEvidenceOutcome.ADVISORY)
        ) { "AI advice must remain advisory and authoritative evidence cannot be advisory" }
    }
}

data class ApkEvidenceConflict(
    val checkId: String,
    val flagged: List<ApkEvidenceRecord>,
    val notFlagged: List<ApkEvidenceRecord>
)

data class ApkSecurityAssessment(
    val status: ApkAssessmentStatus,
    val authoritativeEvidence: List<ApkEvidenceRecord>,
    val findings: List<ApkEvidenceRecord>,
    val conflicts: List<ApkEvidenceConflict>,
    val aiAdvisories: List<ApkEvidenceRecord>
)

/**
 * Combines evidence without allowing model advice to decide the security result.
 * An empty or AI-only input is inconclusive, never safe.
 */
object ApkEvidenceResolver {
    fun assess(records: Iterable<ApkEvidenceRecord>): ApkSecurityAssessment {
        val authoritative = mutableListOf<ApkEvidenceRecord>()
        val advisories = mutableListOf<ApkEvidenceRecord>()
        val grouped = linkedMapOf<String, MutableList<ApkEvidenceRecord>>()

        records.forEach { record ->
            if (record.source == ApkEvidenceSource.AI_ADVISORY) {
                advisories += record
            } else {
                authoritative += record
                grouped.getOrPut(record.checkId) { mutableListOf() } += record
            }
        }

        val conflicts = grouped.entries.mapNotNull { (checkId, evidence) ->
            val flagged = evidence.filter { it.outcome == ApkEvidenceOutcome.FLAGGED }
            val notFlagged = evidence.filter { it.outcome == ApkEvidenceOutcome.NOT_FLAGGED }
            if (flagged.isNotEmpty() && notFlagged.isNotEmpty()) {
                ApkEvidenceConflict(checkId, flagged, notFlagged)
            } else null
        }
        val findings = authoritative.filter { it.outcome == ApkEvidenceOutcome.FLAGGED }
        val status = when {
            conflicts.isNotEmpty() -> ApkAssessmentStatus.CONFLICTING_EVIDENCE
            findings.isNotEmpty() -> ApkAssessmentStatus.FINDINGS_PRESENT
            authoritative.any { it.outcome == ApkEvidenceOutcome.NOT_FLAGGED } ->
                ApkAssessmentStatus.NO_FLAGS_WITHIN_SCOPE
            else -> ApkAssessmentStatus.INSUFFICIENT_EVIDENCE
        }
        return ApkSecurityAssessment(status, authoritative, findings, conflicts, advisories)
    }

    fun fromManifestFindings(findings: Iterable<ApkSecurityFinding>): ApkSecurityAssessment =
        assess(findings.mapNotNull { finding ->
            if (finding.severity == ApkFindingSeverity.INFO ||
                finding.id == "manifest.no_initial_flags"
            ) return@mapNotNull null
            ApkEvidenceRecord(
                checkId = finding.id,
                outcome = if (finding.source == ApkFindingSource.AI_ADVISORY) {
                    ApkEvidenceOutcome.ADVISORY
                } else {
                    ApkEvidenceOutcome.FLAGGED
                },
                source = when (finding.source) {
                    ApkFindingSource.ANDROID_MANIFEST_METADATA ->
                        ApkEvidenceSource.ANDROID_MANIFEST_METADATA
                    ApkFindingSource.DETERMINISTIC_RULE ->
                        ApkEvidenceSource.DETERMINISTIC_RULE
                    ApkFindingSource.AI_ADVISORY -> ApkEvidenceSource.AI_ADVISORY
                },
                detail = finding.evidence
            )
        })
}

package ai.noid.app

/**
 * Produces a portable, plain-text report. Findings remain triage indicators,
 * not proof that a target is exploitable or secure.
 */
object ApkReportFormatter {
    fun format(report: ApkInspectionReport, sourceName: String? = null): String = buildString {
        appendLine("NOID AI - APK SECURITY REVIEW")
        appendLine("================================")
        appendLine("Package: ${report.packageName}")
        appendLine("Version: ${report.versionName ?: "unknown"}")
        appendLine("Minimum SDK: ${report.minSdkVersion ?: "unknown"}")
        appendLine("Target SDK: ${report.targetSdkVersion ?: "unknown"}")
        appendLine("Assessment: ${report.assessment.status}")
        appendLine("Authoritative evidence: ${report.assessment.authoritativeEvidence.size}")
        appendLine("Conflicts: ${report.assessment.conflicts.size}")
        appendLine("AI advisories: ${report.assessment.aiAdvisories.size}")
        sourceName?.takeIf { it.isNotBlank() }?.let { appendLine("Source file: $it") }
        appendLine("Requested permissions: ${report.requestedPermissions.size}")
        appendLine("Components: ${report.components.size}")
        appendLine()
        appendLine("FINDINGS (${report.findings.size})")
        appendLine("--------------------------------")
        report.findings.forEachIndexed { index, finding ->
            appendLine("${index + 1}. [${finding.severity}] [confidence: ${finding.confidence}] ${finding.title}")
            appendLine("ID: ${finding.id}")
            appendLine("Description: ${finding.description}")
            appendLine("Evidence: ${finding.evidence}")
            appendLine("Source: ${finding.source}")
            appendLine("Recommendation: ${finding.recommendation}")
            appendLine()
        }
        appendLine("LIMITATIONS")
        appendLine("This is a static, first-pass manifest review. Findings require manual validation.")
        appendLine("No finding is proof of exploitability; no findings are not proof of security.")
        appendLine("The APK was inspected without installation or execution.")
    }
}

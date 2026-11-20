package ai.noid.app

import org.junit.Assert.assertTrue
import org.junit.Test

class ApkReportFormatterTest {
    @Test
    fun includesEvidenceConfidenceAndSafetyLimitations() {
        val report = ApkInspectionReport(
            packageName = "org.example.sample",
            versionName = "1.2",
            requestedPermissions = listOf("android.permission.INTERNET"),
            components = emptyList(),
            findings = listOf(
                ApkSecurityFinding(
                    id = "manifest.debuggable",
                    severity = ApkFindingSeverity.MEDIUM,
                    title = "Debug enabled",
                    description = "Debug build indicator.",
                    evidence = "FLAG_DEBUGGABLE=true",
                    recommendation = "Disable debugging for production.",
                    confidence = ApkFindingConfidence.HIGH
                )
            ),
            minSdkVersion = 26,
            targetSdkVersion = 36
        )

        val text = ApkReportFormatter.format(report, "sample.apk")

        assertTrue(text.contains("org.example.sample"))
        assertTrue(text.contains("Source file: sample.apk"))
        assertTrue(text.contains("[confidence: HIGH]"))
        assertTrue(text.contains("FLAG_DEBUGGABLE=true"))
        assertTrue(text.contains("No finding is proof of exploitability"))
    }

    @Test
    fun handlesEmptyFindingsAndUnknownMetadata() {
        val report = ApkInspectionReport(
            packageName = "org.example.empty",
            versionName = null,
            requestedPermissions = emptyList(),
            components = emptyList(),
            findings = emptyList()
        )

        val text = ApkReportFormatter.format(report)

        assertTrue(text.contains("Version: unknown"))
        assertTrue(text.contains("Minimum SDK: unknown"))
        assertTrue(text.contains("FINDINGS (0)"))
        assertTrue(text.contains("no findings are not proof of security"))
    }
}

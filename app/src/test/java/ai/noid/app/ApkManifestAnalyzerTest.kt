package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkManifestAnalyzerTest {
    @Test fun reportsDebuggableAndBackupAsReviewFindings() {
        val report = ApkManifestAnalyzer.analyze(
            ApkManifestSnapshot("example.app", "1.0", true, true, emptyList(), emptyList())
        )
        assertTrue(report.findings.any { it.id == "manifest.debuggable" && it.severity == ApkFindingSeverity.MEDIUM })
        assertTrue(report.findings.any { it.id == "manifest.backup_enabled" && it.severity == ApkFindingSeverity.LOW })
    }

    @Test fun flagsExportedComponentWithoutPermissionAsReviewLead() {
        val report = ApkManifestAnalyzer.analyze(
            ApkManifestSnapshot(
                "example.app", "1.0", false, false, emptyList(),
                listOf(ApkExportedComponent("example.app.ShareActivity", ApkComponentType.ACTIVITY, true, null))
            )
        )
        val finding = report.findings.single()
        assertEquals("manifest.exported.activity.example.app.ShareActivity", finding.id)
        assertTrue(finding.description.contains("قد يكون ذلك مقصوداً"))
    }

    @Test fun permissionInventoryIsInformationalAndDeduplicated() {
        val report = ApkManifestAnalyzer.analyze(
            ApkManifestSnapshot("example.app", null, false, false, listOf("CAMERA", "CAMERA"), emptyList())
        )
        assertEquals(listOf("CAMERA"), report.requestedPermissions)
        assertEquals(ApkFindingSeverity.INFO, report.findings.single().severity)
    }

    @Test fun reportsCleartextTrafficAndSdkMetadata() {
        val report = ApkManifestAnalyzer.analyze(
            ApkManifestSnapshot(
                "example.app", "1.0", false, false, emptyList(), emptyList(),
                minSdkVersion = 23,
                targetSdkVersion = 35,
                usesCleartextTraffic = true
            )
        )
        assertEquals(23, report.minSdkVersion)
        assertEquals(35, report.targetSdkVersion)
        assertTrue(report.findings.any { it.id == "manifest.cleartext_traffic" })
    }

    @Test fun cleanSnapshotGetsExplicitLimitedScopeResult() {
        val report = ApkManifestAnalyzer.analyze(
            ApkManifestSnapshot("example.app", "2", false, false, emptyList(), emptyList())
        )
        assertEquals("manifest.no_initial_flags", report.findings.single().id)
        assertTrue(report.findings.single().description.contains("ليست شهادة أمان"))
    }
}

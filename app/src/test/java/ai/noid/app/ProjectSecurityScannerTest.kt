package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSecurityScannerTest {
    @Test fun detectsHardcodedSecretAndRedactsValue() {
        val findings = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/config.properties", "api_key = \"abcd1234-secret-value\""))
        )
        val finding = findings.single()
        assertEquals(ApkFindingSeverity.HIGH, finding.severity)
        assertEquals(ApkFindingConfidence.LOW, finding.confidence)
        assertFalse(finding.evidence.contains("abcd1234-secret-value"))
        assertTrue(finding.evidence.contains("[REDACTED]"))
    }


    @Test fun detectsBearerCredentialsWithoutEchoingTheToken() {
        val token = "eyJhbGciOiJIUzI1NiJ9.fake-payload.signature"
        val findings = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/config.properties", """Authorization: Bearer $token"""))
        )
        val finding = findings.single { it.id.startsWith("source.bearer_token") }
        assertEquals(ApkFindingSeverity.HIGH, finding.severity)
        assertFalse(finding.evidence.contains(token))
        assertTrue(finding.evidence.contains("تم حجب بيانات الاعتماد"))
    }

    @Test fun detectsPrivateKeyHeadersWithoutEchoingKeyMaterial() {
        val keyBody = "PRIVATE-KEY-BODY-DO-NOT-LOG"
        val findings = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/keys.kt", "-----BEGIN PRIVATE KEY-----\n$keyBody"))
        )
        val finding = findings.single { it.id.startsWith("source.private_key_material") }
        assertEquals(ApkFindingSeverity.HIGH, finding.severity)
        assertFalse(finding.evidence.contains(keyBody))
        assertTrue(finding.evidence.contains("تم حجب محتوى المفتاح"))
    }

    @Test fun detectsWebViewRiskIndicatorsWithoutCallingThemCertainVulnerabilities() {
        val findings = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/src/MainActivity.kt", "web.settings.setJavaScriptEnabled(true)\nweb.addJavascriptInterface(bridge, \"Bridge\")"))
        )
        assertTrue(findings.any { it.id.startsWith("source.webview_javascript") })
        assertTrue(findings.any { it.id.startsWith("source.webview_bridge") })
    }

    @Test fun ignoresTraversalAndBinaryFiles() {
        val findings = ProjectSecurityScanner.scan(
            listOf(
                ProjectSourceFile("../secrets.properties", "password=\"not-a-real-secret-123\""),
                ProjectSourceFile("classes.dex", "password=\"not-a-real-secret-123\"")
            )
        )
        assertTrue(findings.isEmpty())
    }

    @Test fun capsFindingCountForVeryNoisyProjects() {
        val source = (1..6_000).joinToString("\n") { "android:debuggable=true" }
        val findings = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/src/main/AndroidManifest.xml", source))
        )
        assertEquals(ProjectSecurityScanner.MAX_FINDINGS, findings.size)
    }

    @Test fun flagsCleartextAndDebugSettings() {
        val findings = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/src/main/AndroidManifest.xml", "<application android:debuggable=\"true\" android:usesCleartextTraffic=\"true\" />"))
        )
        assertTrue(findings.any { it.id.startsWith("source.debuggable") })
        assertTrue(findings.any { it.id.startsWith("source.cleartext_traffic") })
    }

    @Test fun cleartextFindingDoesNotEchoTheUrlOrQueryData() {
        val privateUrl = "http://example.invalid/path?token=do-not-log-this"
        val findings = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/config.properties", """endpoint="$privateUrl""""))
        )
        val finding = findings.single { it.id.startsWith("source.cleartext_traffic") }
        assertFalse(finding.evidence.contains(privateUrl))
        assertFalse(finding.evidence.contains("do-not-log-this"))
        assertTrue(finding.evidence.contains("تم حجب نص السطر"))
    }

    @Test fun skipsSourceFilesOverThePerFileByteLimit() {
        val oversized = "x".repeat((ProjectFilePolicy.MAX_TEXT_FILE_BYTES + 1).toInt())
        val findings = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/config.properties", oversized))
        )
        assertTrue(findings.isEmpty())
    }
}

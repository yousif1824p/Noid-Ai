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

    @Test fun detectsBearerCredentialsInCommonKotlinHeaderCallsWithoutEchoingTheToken() {
        val token = "eyJhbGciOiJIUzI1NiJ9.kotlin-payload.signature"
        val findings = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/src/Network.kt", """request.header("Authorization", "Bearer $token")"""))
        )
        val finding = findings.single { it.id.startsWith("source.bearer_token") }
        assertEquals(ApkFindingSeverity.HIGH, finding.severity)
        assertFalse(finding.evidence.contains(token))
        assertTrue(finding.evidence.contains("تم حجب بيانات الاعتماد"))
    }

    @Test fun detectsProviderTokenFormatsAndRedactsTheirValues() {
        val githubClassic = "ghp_" + "A".repeat(36)
        val githubFineGrained = "github_pat_" + "B".repeat(30)
        val slack = "xoxb-" + "C".repeat(20)
        val google = "AIza" + "D".repeat(35)
        val source = listOf(githubClassic, githubFineGrained, slack, google)
            .joinToString("\n") { """credential = "$it"""" }

        val findings = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/config.properties", source))
        )

        assertEquals(4, findings.size)
        assertTrue(findings.any { it.id.startsWith("source.github_token") })
        assertTrue(findings.any { it.id.startsWith("source.slack_token") })
        assertTrue(findings.any { it.id.startsWith("source.google_api_key") })
        listOf(githubClassic, githubFineGrained, slack, google).forEach { token ->
            assertTrue(findings.none { it.evidence.contains(token) })
        }
        assertTrue(findings.all { it.evidence.contains("تم حجب بيانات الاعتماد") })
        assertTrue(findings.all { it.severity == ApkFindingSeverity.HIGH })
    }

    @Test fun detectsGitLabNpmAndStripeCredentialsWithoutEchoingValues() {
        val gitlab = "glpat-" + "G".repeat(24)
        val npm = "npm_" + "N".repeat(36)
        val stripe = "sk_live_" + "S".repeat(24)
        val source = listOf(gitlab, npm, stripe).joinToString("\\n") { """credential = "$it"""" }
        val findings = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/config.properties", source))
        )
        assertEquals(3, findings.size)
        assertTrue(findings.any { it.id.startsWith("source.gitlab_token") })
        assertTrue(findings.any { it.id.startsWith("source.npm_token") })
        assertTrue(findings.any { it.id.startsWith("source.stripe_secret") })
        listOf(gitlab, npm, stripe).forEach { token ->
            assertTrue(findings.none { it.evidence.contains(token) })
        }
        assertTrue(findings.all { it.severity == ApkFindingSeverity.HIGH })
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

    @Test fun reportsIncompleteScanWhenDirectInputExceedsFileLimit() {
        val files = (1..ProjectSecurityScanner.MAX_INPUT_FILES).map { index ->
            ProjectSourceFile("app/config-$index.properties", "ordinary configuration")
        } + ProjectSourceFile(
            "app/last.properties",
            """api_key = "late-secret-value-12345""""
        )

        val findings = ProjectSecurityScanner.scan(files)
        val limitFinding = findings.single { it.id == "source.scan_input_limit" }

        assertEquals(ApkFindingSeverity.LOW, limitFinding.severity)
        assertTrue(limitFinding.description.contains("لم يشمل الفحص جميع الملفات"))
        assertTrue(limitFinding.evidence.contains("501"))
        assertTrue(limitFinding.evidence.contains("لم تُفحص 1 ملفات"))
        assertFalse(findings.any { it.evidence.contains("late-secret-value-12345") })
    }

    @Test fun doesNotReportInputLimitWhenAllFilesAreWithinBound() {
        val files = (1..ProjectSecurityScanner.MAX_INPUT_FILES).map { index ->
            ProjectSourceFile("app/config-$index.properties", "ordinary configuration")
        }

        assertTrue(ProjectSecurityScanner.scan(files).isEmpty())
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

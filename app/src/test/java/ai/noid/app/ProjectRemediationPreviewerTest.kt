package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRemediationPreviewerTest {
    @Test fun previewsManifestDebuggableChangeAndRetestsInMemory() {
        val content = """<application
    android:label="NOID"
    android:debuggable="true"
    android:allowBackup="false" />"""
        val finding = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/src/main/AndroidManifest.xml", content))
        ).single { it.id.startsWith("source.debuggable:") }

        val preview = ProjectRemediationPreviewer.preview(
            ProjectSourceFile("app/src/main/AndroidManifest.xml", content),
            finding
        )

        assertTrue(preview.supported)
        assertTrue(preview.proposedContent!!.contains("""android:debuggable="false""""))
        assertTrue(preview.proposedContent.contains("""android:label="NOID""""))
        assertEquals(1, preview.diff!!.additions)
        assertEquals(1, preview.diff.removals)
        assertEquals(0, preview.remainingMatchingFindings)
        assertTrue(preview.resolvesFindingInPreview)
        assertTrue(content.contains("""android:debuggable="true""""))
    }

    @Test fun previewsCleartextManifestAttributeAndRetests() {
        val content = """<application android:usesCleartextTraffic="true" />"""
        val finding = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/src/main/AndroidManifest.xml", content))
        ).single { it.id.startsWith("source.cleartext_traffic:") }

        val preview = ProjectRemediationPreviewer.preview(
            ProjectSourceFile("app/src/main/AndroidManifest.xml", content),
            finding
        )

        assertTrue(preview.supported)
        assertTrue(preview.proposedContent!!.contains("""usesCleartextTraffic="false""""))
        assertEquals(0, preview.remainingMatchingFindings)
    }

    @Test fun doesNotClaimResolutionWhenAnotherMatchingLineRemains() {
        val content = """<application android:debuggable="true">
    <activity android:debuggable="true" />
</application>"""
        val finding = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/src/main/AndroidManifest.xml", content))
        ).first { it.id.startsWith("source.debuggable:") }

        val preview = ProjectRemediationPreviewer.preview(
            ProjectSourceFile("app/src/main/AndroidManifest.xml", content),
            finding
        )

        assertTrue(preview.supported)
        assertEquals(1, preview.remainingMatchingFindings)
        assertFalse(preview.resolvesFindingInPreview)
    }

    @Test fun refusesSecretAndHttpUrlAutoRemediation() {
        val secretFile = ProjectSourceFile("app/config.xml", """<item password="not-a-real-secret-123" />""")
        val secret = ApkSecurityFinding(
            "source.hardcoded_secret:app/config.xml:1", ApkFindingSeverity.HIGH,
            "secret", "description", "redacted", "manual", ApkFindingConfidence.LOW
        )
        val secretPreview = ProjectRemediationPreviewer.preview(secretFile, secret)
        assertFalse(secretPreview.supported)
        assertNull(secretPreview.proposedContent)

        val urlContent = """<string name="endpoint">http://example.invalid</string>"""
        val urlFinding = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/config.xml", urlContent))
        ).single()
        val urlPreview = ProjectRemediationPreviewer.preview(
            ProjectSourceFile("app/config.xml", urlContent), urlFinding
        )
        assertFalse(urlPreview.supported)
        assertNull(urlPreview.proposedContent)
    }

    @Test fun refusesWrongFileWrongPathAndAmbiguousAttribute() {
        val content = """<application android:debuggable="true" />"""
        val finding = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/src/main/AndroidManifest.xml", content))
        ).single()

        val wrongFile = ProjectRemediationPreviewer.preview(
            ProjectSourceFile("app/config.properties", content), finding
        )
        assertFalse(wrongFile.supported)
        assertNull(wrongFile.diff)

        val wrongPath = ProjectRemediationPreviewer.preview(
            ProjectSourceFile("other/AndroidManifest.xml", content), finding
        )
        assertFalse(wrongPath.supported)

        val ambiguousContent = """<application android:debuggable="true" android:debuggable='true' />"""
        val ambiguousFinding = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/src/main/AndroidManifest.xml", ambiguousContent))
        ).first()
        val ambiguous = ProjectRemediationPreviewer.preview(
            ProjectSourceFile("app/src/main/AndroidManifest.xml", ambiguousContent),
            ambiguousFinding
        )
        assertFalse(ambiguous.supported)
        assertNull(ambiguous.proposedContent)
    }

    @Test fun neverWritesAndDiffIsAvailableOnlyForSupportedPreview() {
        val content = """<application android:debuggable="true" />"""
        val finding = ProjectSecurityScanner.scan(
            listOf(ProjectSourceFile("app/src/main/AndroidManifest.xml", content))
        ).single()
        val preview = ProjectRemediationPreviewer.preview(
            ProjectSourceFile("app/src/main/AndroidManifest.xml", content), finding
        )

        assertNotNull(preview.diff)
        assertEquals(content, preview.originalContent)
        assertTrue(content.contains("""android:debuggable="true""""))
    }
}

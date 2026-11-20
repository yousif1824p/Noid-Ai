package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPreviewSecretRedactorTest {
    @Test fun redactsCommonQuotedSecretAssignments() {
        val inputs = listOf(
            """api_key="alpha-secret"""",
            """client-secret: 'bravo-secret'""",
            """PASSWORD = "charlie-secret"""",
            """access_token='delta-secret'"""
        )
        inputs.forEach { line ->
            val redacted = ProjectPreviewSecretRedactor.redact(line)
            assertTrue(redacted.contains("[REDACTED]"))
            assertFalse(redacted.contains("alpha-secret") || redacted.contains("bravo-secret") ||
                redacted.contains("charlie-secret") || redacted.contains("delta-secret"))
        }
    }

    @Test fun redactsEscapedQuotesWithoutLeavingASecretSuffix() {
        val doubleQuoted = "password=\"prefix\\\"-suffix-secret\""
        val singleQuoted = "client_secret='prefix\\\'-suffix-secret'"
        assertEquals("password=\"[REDACTED]\"", ProjectPreviewSecretRedactor.redact(doubleQuoted))
        assertEquals("client_secret='[REDACTED]'", ProjectPreviewSecretRedactor.redact(singleQuoted))
    }

    @Test fun preservesNonSecretTextAndDoesNotRedactUnrelatedValues() {
        val line = """android:label="NOID" endpoint="https://example.invalid""""
        assertEquals(line, ProjectPreviewSecretRedactor.redact(line))
    }

    @Test fun redactsOnlyTheSecretValueAndPreservesAssignmentShape() {
        val line = """password="do-not-show" """
        assertEquals("""password="[REDACTED]" """, ProjectPreviewSecretRedactor.redact(line))
    }

    @Test fun redactsUnquotedSecretsAndAuthorizationBearerHeaders() {
        val inputs = listOf(
            "api_key=plain-secret-value",
            "refresh_token: refresh-secret-value",
            "Authorization: Bearer bearer-secret-value",
            "private_key = private-secret-value"
        )
        inputs.forEach { line ->
            val redacted = ProjectPreviewSecretRedactor.redact(line)
            assertTrue(redacted.contains("[REDACTED]"))
            assertFalse(
                redacted.contains("plain-secret-value") ||
                    redacted.contains("refresh-secret-value") ||
                    redacted.contains("bearer-secret-value") ||
                    redacted.contains("private-secret-value")
            )
        }
    }

    @Test fun preservesTrailingJsonAndYamlDelimitersWhenRedactingUnquotedValues() {
        assertEquals(
            """{"api_key":[REDACTED]}""",
            ProjectPreviewSecretRedactor.redact("""{"api_key":plain-secret}""")
        )
        assertEquals(
            "password: [REDACTED] # keep comment",
            ProjectPreviewSecretRedactor.redact("password: plain-secret # keep comment")
        )
    }
}

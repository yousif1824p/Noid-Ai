package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteSseEnvelopeParserTest {
    private fun json(sequence: String = "1", extra: String = "") =
        """{"request_id":"req-1","protocol_version":"1","sequence":$sequence$extra}"""

    @Test fun parsesRequiredFieldsAndOptionalText() {
        val body = """{"request_id":"req-1","protocol_version":"1","sequence":1,"text":"مرحبا NOID"}"""
        val parsed = RemoteSseEnvelopeParser.parse(RemoteSseFrame("delta", body))
        assertEquals("req-1", parsed.requestId)
        assertEquals(1L, parsed.sequence)
        assertEquals("مرحبا NOID", parsed.text)
    }

    @Test fun rejectsMalformedNonObjectAndUnknownFields() {
        listOf("{", "[]", json(extra = ""","admin":true"""),
            json().replace("\"request_id\":\"req-1\",", "")
        ).forEach { body ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteSseEnvelopeParser.parse(RemoteSseFrame("started", body))
            }
        }
    }

    @Test fun rejectsTypeCoercionAndNullOptionalFields() {
        listOf(
            json().replace("\"request_id\":\"req-1\"", "\"request_id\":7"),
            json().replace("\"protocol_version\":\"1\"", "\"protocol_version\":1"),
            json(extra = ""","text":null"""),
            json(extra = ""","error_code":null""")
        ).forEach { body ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteSseEnvelopeParser.parse(RemoteSseFrame("delta", body))
            }
        }
    }

    @Test fun acceptsOnlyPositiveIntegralInRangeSequences() {
        listOf("0", "-1", "1.0", "1e2", "\"1\"", "9223372036854775808").forEach { sequence ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteSseEnvelopeParser.parse(RemoteSseFrame("started", json(sequence)))
            }
        }
    }

    @Test fun enforcesRequestIdVersionAndTextBounds() {
        val longId = json().replace("req-1", "x".repeat(65))
        val wrongVersion = json().replace("\"protocol_version\":\"1\"", "\"protocol_version\":\"2\"")
        val longText = """{"request_id":"req-1","protocol_version":"1","sequence":1,"text":"""" +
            "x".repeat(RemoteSseEnvelopeParser.MAX_TEXT_CHARS + 1) + """"}"""
        listOf(longId, wrongVersion, longText).forEach { body ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteSseEnvelopeParser.parse(RemoteSseFrame("delta", body))
            }
        }
    }

    @Test fun validatesCompleteDocumentAcrossFramingParsingAndLifecycle() {
        val document = """
            event: started
            data: {"request_id":"req-1","protocol_version":"1","sequence":1}

            event: delta
            data: {"request_id":"req-1","protocol_version":"1","sequence":2,"text":"hello"}

            event: completed
            data: {"request_id":"req-1","protocol_version":"1","sequence":3}

        """.trimIndent() + "\n"
        val result = RemoteSseDocumentValidator.validate(document, "req-1")
        assertEquals(listOf("started", "delta", "completed"), result.map { it.frame.event })
        assertEquals("hello", result[1].envelope.text)
    }

    @Test fun rejectsWrongRequestAndMissingTerminalEvent() {
        val wrongRequest = """
            event: started
            data: {"request_id":"other","protocol_version":"1","sequence":1}

            event: completed
            data: {"request_id":"other","protocol_version":"1","sequence":2}

        """.trimIndent() + "\n"
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseDocumentValidator.validate(wrongRequest, "req-1")
        }
        val unfinished = """
            event: started
            data: {"request_id":"req-1","protocol_version":"1","sequence":1}

        """.trimIndent() + "\n"
        assertThrows(IllegalStateException::class.java) {
            RemoteSseDocumentValidator.validate(unfinished, "req-1")
        }
    }

    @Test fun rejectsEventAndEnvelopeTypeMismatch() {
        val invalid = """
            event: started
            data: {"request_id":"req-1","protocol_version":"1","sequence":1,"text":"unexpected"}

            event: completed
            data: {"request_id":"req-1","protocol_version":"1","sequence":2}

        """.trimIndent() + "\n"
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseDocumentValidator.validate(invalid, "req-1")
        }
    }
}

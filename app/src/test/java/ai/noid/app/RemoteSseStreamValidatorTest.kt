package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteSseStreamValidatorTest {
    private fun envelope(
        sequence: Long,
        text: String? = null,
        errorCode: String? = null,
        requestId: String = "req-1",
        version: String = "1"
    ) = RemoteSseEnvelope(requestId, version, sequence, text, errorCode)

    private fun frame(event: String) = RemoteSseFrame(event, "{}")

    @Test fun acceptsStartedDeltasAndExactlyOneCompletion() {
        val validator = RemoteSseStreamValidator("req-1")
        validator.accept(frame("started"), envelope(1))
        validator.accept(frame("delta"), envelope(2, text = "hello "))
        validator.accept(frame("delta"), envelope(3, text = "NOID"))
        validator.accept(frame("completed"), envelope(4))
        validator.finish()
    }

    @Test fun acceptsAValidatedTerminalError() {
        val validator = RemoteSseStreamValidator("req-1")
        validator.accept(frame("started"), envelope(1))
        validator.accept(frame("error"), envelope(2, errorCode = "unavailable"))
        validator.finish()
    }

    @Test fun rejectsWrongRequestAndUnknownProtocolVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseStreamValidator("req-1").accept(frame("started"), envelope(1, requestId = "req-2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseStreamValidator("req-1").accept(frame("started"), envelope(1, version = "2"))
        }
    }

    @Test fun rejectsDuplicateOrOutOfOrderSequences() {
        val validator = RemoteSseStreamValidator("req-1")
        validator.accept(frame("started"), envelope(2))
        assertThrows(IllegalArgumentException::class.java) {
            validator.accept(frame("delta"), envelope(2, text = "duplicate"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.accept(frame("delta"), envelope(1, text = "old"))
        }
    }

    @Test fun rejectsDeltaBeforeStartedAndRepeatedStarted() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseStreamValidator("req-1").accept(frame("delta"), envelope(1, text = "early"))
        }
        val validator = RemoteSseStreamValidator("req-1")
        validator.accept(frame("started"), envelope(1))
        assertThrows(IllegalArgumentException::class.java) {
            validator.accept(frame("started"), envelope(2))
        }
    }

    @Test fun rejectsMissingDeltaTextAndUnexpectedPayloads() {
        val validator = RemoteSseStreamValidator("req-1")
        validator.accept(frame("started"), envelope(1))
        assertThrows(IllegalArgumentException::class.java) {
            validator.accept(frame("delta"), envelope(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.accept(frame("completed"), envelope(2, text = "unexpected"))
        }
    }

    @Test fun enforcesAggregateOutputLimit() {
        val validator = RemoteSseStreamValidator("req-1")
        validator.accept(frame("started"), envelope(1))
        validator.accept(frame("delta"), envelope(2, text = "x".repeat(32_000)))
        assertThrows(IllegalArgumentException::class.java) {
            validator.accept(frame("delta"), envelope(3, text = "y"))
        }
    }

    @Test fun acceptsServerOutputLimitErrorCode() {\n        val validator = RemoteSseStreamValidator("req-1")\n        validator.accept(frame("started"), envelope(1))\n        validator.accept(frame("error"), envelope(2, errorCode = "output_limit"))\n        validator.finish()\n    }\n\n    @Test fun rejectsUnknownErrorCodesAndErrorText() {
        val validator = RemoteSseStreamValidator("req-1")
        validator.accept(frame("started"), envelope(1))
        assertThrows(IllegalArgumentException::class.java) {
            validator.accept(frame("error"), envelope(2, text = "private detail", errorCode = "oops"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validator.accept(frame("error"), envelope(2, errorCode = "stack_trace"))
        }
    }

    @Test fun rejectsEventsAfterTerminalAndMissingTerminal() {
        val validator = RemoteSseStreamValidator("req-1")
        validator.accept(frame("started"), envelope(1))
        validator.accept(frame("completed"), envelope(2))
        assertThrows(IllegalStateException::class.java) {
            validator.accept(frame("delta"), envelope(3, text = "late"))
        }
        validator.finish()

        val incomplete = RemoteSseStreamValidator("req-1")
        incomplete.accept(frame("started"), envelope(1))
        assertThrows(IllegalStateException::class.java) { incomplete.finish() }
    }

    @Test fun rejectsInvalidExpectedRequestId() {
        assertThrows(IllegalArgumentException::class.java) { RemoteSseStreamValidator("") }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseStreamValidator("x".repeat(RemoteSseStreamValidator.MAX_REQUEST_ID_CHARS + 1))
        }
    }

    @Test fun allowsOutputAtExactMaximum() {
        val validator = RemoteSseStreamValidator("req-1")
        validator.accept(frame("started"), envelope(1))
        validator.accept(frame("delta"), envelope(2, text = "x".repeat(32_000)))
        validator.accept(frame("completed"), envelope(3))
        validator.finish()
        assertEquals(32_000, RemoteSseStreamValidator.MAX_OUTPUT_CHARS)
    }
}

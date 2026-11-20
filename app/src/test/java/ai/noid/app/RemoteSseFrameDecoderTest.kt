package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteSseFrameDecoderTest {
    @Test fun decodesMultipleFramesAndMultilineData() {
        val frames = RemoteSseFrameDecoder.decode(
            "event: started\ndata: {\"seq\":1}\n\n" +
                "event: delta\ndata: first\ndata: second\n\n"
        )
        assertEquals(
            listOf(RemoteSseFrame("started", "{\"seq\":1}"), RemoteSseFrame("delta", "first\nsecond")),
            frames
        )
    }

    @Test fun acceptsCrLfAndIgnoresComments() {
        val frames = RemoteSseFrameDecoder.decode(": heartbeat\r\nevent: completed\r\ndata: {}\r\n\r\n")
        assertEquals(listOf(RemoteSseFrame("completed", "{}")), frames)
    }

    @Test fun acceptsCarriageReturnOnlyLineEndings() {
        val frames = RemoteSseFrameDecoder.decode("event: started\rdata: {}\r\revent: completed\rdata: {}\r\r")
        assertEquals(
            listOf(RemoteSseFrame("started", "{}"), RemoteSseFrame("completed", "{}")),
            frames
        )
    }

    @Test fun emptyDocumentAndHeartbeatOnlyDocumentProduceNoFrames() {
        assertEquals(emptyList<RemoteSseFrame>(), RemoteSseFrameDecoder.decode(""))
        assertEquals(emptyList<RemoteSseFrame>(), RemoteSseFrameDecoder.decode(": ping\n\n"))
    }

    @Test fun rejectsUnknownEventAndUnsupportedFields() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseFrameDecoder.decode("event: tool_call\ndata: {}\n\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseFrameDecoder.decode("id: 5\nevent: delta\ndata: x\n\n")
        }
    }

    @Test fun rejectsDuplicateEventAndMissingData() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseFrameDecoder.decode("event: delta\nevent: error\ndata: x\n\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseFrameDecoder.decode("event: delta\n\n")
        }
    }

    @Test fun rejectsIncompleteFramesAndLines() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseFrameDecoder.decode("event: delta\ndata: x\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseFrameDecoder.decode("event: delta\ndata: x")
        }
    }

    @Test fun enforcesDocumentAndEventBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseFrameDecoder.decode("x".repeat(RemoteSseFrameDecoder.MAX_DOCUMENT_CHARS + 1))
        }
        val oversized = "event: delta\ndata: " +
            "x".repeat(RemoteSseFrameDecoder.MAX_EVENT_CHARS) + "\n\n"
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseFrameDecoder.decode(oversized)
        }
    }

    @Test fun enforcesMaximumFrameCount() {
        val document = buildString {
            repeat(RemoteSseFrameDecoder.MAX_EVENTS + 1) { append("event: delta\ndata: x\n\n") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSseFrameDecoder.decode(document)
        }
    }
}

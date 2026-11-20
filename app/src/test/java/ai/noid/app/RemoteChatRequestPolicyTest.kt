package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteChatRequestPolicyTest {
    private fun request(messages: List<RemoteChatMessage> = listOf(RemoteChatMessage("user", "Hello"))) =
        RemoteChatRequest("req-01", messages)

    @Test fun acceptsBoundedTextOnlyConversation() {
        val input = request(
            listOf(
                RemoteChatMessage("user", "مرحبا"),
                RemoteChatMessage("assistant", "أهلاً")
            )
        )
        assertSame(input, RemoteChatRequestPolicy.validate(input))
    }

    @Test fun rejectsInvalidRequestIds() {
        listOf("", "contains space", "x".repeat(65), "id/with/slash").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteChatRequestPolicy.validate(request().copy(requestId = id))
            }
        }
    }

    @Test fun rejectsEmptyTooManyOrUserlessMessages() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatRequestPolicy.validate(request(emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatRequestPolicy.validate(request(List(21) { RemoteChatMessage("user", "x") }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatRequestPolicy.validate(request(listOf(RemoteChatMessage("assistant", "reply"))))
        }
    }

    @Test fun rejectsUnsupportedRolesAndBlankMessages() {
        listOf(
            listOf(RemoteChatMessage("system", "secret system prompt"), RemoteChatMessage("user", "hi")),
            listOf(RemoteChatMessage("tool", "execute")),
            listOf(RemoteChatMessage("user", "  "))
        ).forEach { messages ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteChatRequestPolicy.validate(request(messages))
            }
        }
    }

    @Test fun rejectsOversizedMessageAndAggregateUtf8Context() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatRequestPolicy.validate(
                request(listOf(RemoteChatMessage("user", "x".repeat(32_001))))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatRequestPolicy.validate(
                request(listOf(
                    RemoteChatMessage("user", "ع".repeat(16_385)),
                    RemoteChatMessage("assistant", "ع".repeat(16_385))
                ))
            )
        }
    }

    @Test fun acceptsContextAtExactUtf8ByteLimit() {
        val arabic = "ع".repeat(16_384)
        val exact = listOf(
            RemoteChatMessage("user", arabic),
            RemoteChatMessage("assistant", arabic)
        )
        RemoteChatRequestPolicy.validate(request(exact))
        assertEquals(65_536, exact.sumOf { it.content.toByteArray(Charsets.UTF_8).size })
    }

    @Test fun enforcesSerializedBodyByteLimit() {
        RemoteChatRequestPolicy.validateSerializedBodySize(byteArrayOf(0x7b))
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatRequestPolicy.validateSerializedBodySize(ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatRequestPolicy.validateSerializedBodySize(
                ByteArray(RemoteChatRequestPolicy.MAX_BODY_BYTES + 1)
            )
        }
    }
}

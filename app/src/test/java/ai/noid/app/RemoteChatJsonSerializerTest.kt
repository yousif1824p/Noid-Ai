package ai.noid.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class RemoteChatJsonSerializerTest {
    @Test
    fun serializesOnlyRequestIdAndPlainTextMessages() {
        val request = RemoteChatRequest(
            requestId = "req-123",
            messages = listOf(
                RemoteChatMessage("user", "مرحبا \"NOID\""),
                RemoteChatMessage("assistant", "أهلاً بك")
            )
        )

        val body = RemoteChatJsonSerializer.serialize(request)
        val json = JSONObject(String(body, StandardCharsets.UTF_8))
        val rootKeys = mutableSetOf<String>()
        val rootIterator = json.keys()
        while (rootIterator.hasNext()) rootKeys += rootIterator.next()

        assertEquals(setOf("request_id", "messages"), rootKeys)
        assertEquals("req-123", json.getString("request_id"))
        val messages = json.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals(setOf("role", "content"), messages.getJSONObject(0).keys().asSequence().toSet())
        assertEquals("مرحبا \"NOID\"", messages.getJSONObject(0).getString("content"))
        assertEquals("أهلاً بك", messages.getJSONObject(1).getString("content"))
        assertFalse(String(body, StandardCharsets.UTF_8).contains("tool"))
    }

    @Test
    fun rejectsUnsupportedRolesBeforeSerialization() {
        val request = RemoteChatRequest(
            requestId = "req-124",
            messages = listOf(
                RemoteChatMessage("user", "hello"),
                RemoteChatMessage("tool", "run command")
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatJsonSerializer.serialize(request)
        }
    }

    @Test
    fun preservesUtf8AndEnforcesAggregateContextLimit() {
        val accepted = RemoteChatRequest(
            requestId = "utf8-boundary",
            messages = listOf(
                RemoteChatMessage("user", "ع".repeat(16_384)),
                RemoteChatMessage("assistant", "ع".repeat(16_384))
            )
        )
        val bytes = RemoteChatJsonSerializer.serialize(accepted)
        assertEquals(65_536, accepted.messages.sumOf {
            it.content.toByteArray(StandardCharsets.UTF_8).size
        })
        assertTrue(bytes.isNotEmpty())

        val rejected = accepted.copy(
            messages = listOf(
                RemoteChatMessage("user", "ع".repeat(16_385)),
                RemoteChatMessage("assistant", "ع".repeat(16_385))
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatJsonSerializer.serialize(rejected)
        }
    }
}

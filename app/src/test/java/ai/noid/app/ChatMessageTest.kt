package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTest {
    @Test
    fun messageRetainsTextAndSender() {
        val userMessage = ChatMessage("Hello NOID", true)
        val assistantMessage = ChatMessage("Welcome", false)

        assertEquals("Hello NOID", userMessage.text)
        assertTrue(userMessage.fromUser)
        assertEquals("Welcome", assistantMessage.text)
        assertFalse(assistantMessage.fromUser)
    }

    @Test
    fun messageHasGeneratedIdDefault() {
        val message = ChatMessage("Stored locally", true)
        assertEquals(0L, message.id)
        assertTrue(message.createdAt > 0L)
    }
}

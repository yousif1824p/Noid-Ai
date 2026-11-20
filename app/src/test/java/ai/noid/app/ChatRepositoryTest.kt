package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRepositoryTest {
    @Test
    fun sendMessageTrimsTextAndIgnoresBlankInput() = runBlocking {
        val dao = FakeChatMessageDao()
        val repository = ChatRepository(dao)

        repository.sendMessage("  مرحباً NOID  ")
        repository.sendMessage("   ")

        assertEquals(1, dao.messages.value.size)
        assertEquals("مرحباً NOID", dao.messages.value.single().text)
        assertEquals(true, dao.messages.value.single().fromUser)
    }

    @Test
    fun returnsRecentConversationInChronologicalOrder() = runBlocking {
        val dao = FakeChatMessageDao()
        val repository = ChatRepository(dao)
        repository.sendMessage("واحد")
        repository.sendMessage("اثنان")
        repository.sendMessage("ثلاثة")

        val recent = repository.currentConversation(limit = 2)

        assertEquals(listOf("اثنان", "ثلاثة"), recent.map { it.text })
    }

    @Test
    fun storesAssistantResponseAsAssistantMessage() = runBlocking {
        val dao = FakeChatMessageDao()
        val repository = ChatRepository(dao)

        repository.appendAssistantResponse("  رد حقيقي من المحرك  ")

        val saved = dao.messages.value.single()
        assertEquals("رد حقيقي من المحرك", saved.text)
        assertEquals(false, saved.fromUser)
    }

    @Test
    fun searchIsCaseInsensitiveAndReturnsNewestMatchesFirst() = runBlocking {
        val dao = FakeChatMessageDao()
        val repository = ChatRepository(dao)
        repository.sendMessage("مرحباً NOID")
        repository.sendMessage("رسالة أخرى")
        repository.sendMessage("NOID يعمل محلياً")

        val results = repository.searchMessages("noid")

        assertEquals(listOf("NOID يعمل محلياً", "مرحباً NOID"), results.map { it.text })
    }

    @Test
    fun blankSearchReturnsNoResults() = runBlocking {
        val repository = ChatRepository(FakeChatMessageDao())

        assertEquals(emptyList<ChatMessage>(), repository.searchMessages("   "))
    }

    @Test
    fun clearConversationRemovesAllMessages() = runBlocking {
        val dao = FakeChatMessageDao()
        val repository = ChatRepository(dao)
        repository.sendMessage("رسالة للاختبار")

        repository.clearConversation()

        assertEquals(emptyList<ChatMessage>(), dao.messages.value)
    }

    private class FakeChatMessageDao : ChatMessageDao {
        val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<ChatMessage>> = messages

        override suspend fun getRecent(limit: Int): List<ChatMessage> =
            messages.value.sortedWith(compareByDescending<ChatMessage> { it.createdAt }.thenByDescending { it.id })
                .take(limit)

        override suspend fun search(query: String, limit: Int): List<ChatMessage> =
            messages.value.filter { it.text.contains(query, ignoreCase = true) }
                .sortedWith(compareByDescending<ChatMessage> { it.createdAt }.thenByDescending { it.id })
                .take(limit)

        override suspend fun insert(message: ChatMessage): Long {
            val stored = message.copy(id = nextId++)
            messages.value = messages.value + stored
            return stored.id
        }

        override suspend fun deleteAll() {
            messages.value = emptyList()
        }
    }
}

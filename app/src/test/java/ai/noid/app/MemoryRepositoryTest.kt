package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryRepositoryTest {
    @Test
    fun savesNormalizedMemoryOnlyWhenExplicitlyRequested() = runBlocking {
        val dao = FakeUserMemoryDao()
        val repository = MemoryRepository(dao)

        repository.saveApprovedMemory(
            title = "  تفضيل اللغة ",
            content = "  أفضل الشرح بالعربية  ",
            source = "  موافقة المستخدم "
        )

        val saved = dao.memories.value.single()
        assertEquals("تفضيل اللغة", saved.title)
        assertEquals("أفضل الشرح بالعربية", saved.content)
        assertEquals("موافقة المستخدم", saved.source)
    }

    @Test
    fun rejectsBlankMemoryFields() {
        val repository = MemoryRepository(FakeUserMemoryDao())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.saveApprovedMemory(
                    title = " ",
                    content = "محتوى",
                    source = "موافقة المستخدم"
                )
            }
        }
    }

    @Test
    fun searchesTitleAndContentWithoutBlankQueryMatches() = runBlocking {
        val dao = FakeUserMemoryDao()
        val repository = MemoryRepository(dao)
        repository.saveApprovedMemory("لغة مفضلة", "أريد الشرح بالعربية", "موافقة")
        repository.saveApprovedMemory("مشروع", "تطبيق أندرويد", "موافقة")

        assertEquals(listOf("لغة مفضلة"), repository.searchMemories("العربية").map { it.title })
        assertEquals(emptyList<UserMemory>(), repository.searchMemories("   "))
    }

    @Test
    fun capsSearchQueryAndResultCount() = runBlocking {
        val dao = FakeUserMemoryDao()
        val repository = MemoryRepository(dao)
        repeat(105) { index ->
            repository.saveApprovedMemory("عنصر $index", "كلمة مشتركة", "موافقة")
        }

        val results = repository.searchMemories("كلمة مشتركة", limit = 500)
        assertEquals(MemoryRepository.MAX_SEARCH_RESULTS, results.size)

        repository.searchMemories("س".repeat(150))
        assertEquals(MemoryRepository.MAX_SEARCH_QUERY_LENGTH, dao.lastSearchQuery?.length)
    }

    @Test
    fun assistantContextIsBoundedAndUsesNewestApprovedMemories() = runBlocking {
        val dao = FakeUserMemoryDao()
        val repository = MemoryRepository(dao)
        repeat(25) { index ->
            repository.saveApprovedMemory("Memory $index", "x".repeat(1_000), "approved")
        }

        val context = repository.recentContext()
        val contextCharacters = context.sumOf {
            it.title.length + it.content.length + it.source.length
        }

        assertEquals(true, context.size in 1..MemoryRepository.MAX_CONTEXT_MEMORIES)
        assertEquals("Memory 24", context.first().title)
        assertEquals(true, contextCharacters <= MemoryRepository.MAX_CONTEXT_CHARACTERS)
        assertEquals(1_000, dao.memories.value.last().content.length)
    }

    @Test
    fun updatesMemoryAndRefreshesTimestamp() = runBlocking {
        val dao = FakeUserMemoryDao()
        val repository = MemoryRepository(dao)
        val id = repository.saveApprovedMemory("عنوان", "قديم", "موافقة")
        val oldTimestamp = dao.memories.value.single().updatedAt

        repository.updateMemory(id, " عنوان جديد ", " محتوى جديد ", " موافقة المستخدم ")

        val updated = dao.memories.value.single()
        assertEquals("عنوان جديد", updated.title)
        assertEquals("محتوى جديد", updated.content)
        assertEquals("موافقة المستخدم", updated.source)
        assertEquals(true, updated.updatedAt >= oldTimestamp)
    }

    @Test
    fun deletesOnlyRequestedMemory() = runBlocking {
        val dao = FakeUserMemoryDao()
        val repository = MemoryRepository(dao)
        val firstId = repository.saveApprovedMemory("الأول", "محتوى 1", "موافقة")
        repository.saveApprovedMemory("الثاني", "محتوى 2", "موافقة")

        repository.deleteMemory(firstId)

        assertEquals(listOf("الثاني"), dao.memories.value.map { it.title })
    }

    private class FakeUserMemoryDao : UserMemoryDao {
        val memories = MutableStateFlow<List<UserMemory>>(emptyList())
        private var nextId = 1L
        var lastSearchQuery: String? = null

        override fun observeAll(): Flow<List<UserMemory>> = memories

        override suspend fun getAll(): List<UserMemory> = memories.value

        override suspend fun getRecent(limit: Int): List<UserMemory> =
            memories.value.sortedWith(
                compareByDescending<UserMemory> { it.updatedAt }.thenByDescending { it.id }
            ).take(limit)

        override suspend fun search(query: String, limit: Int): List<UserMemory> {
            lastSearchQuery = query
            return memories.value.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.content.contains(query, ignoreCase = true)
            }.sortedByDescending { it.updatedAt }.take(limit)
        }

        override suspend fun insert(memory: UserMemory): Long {
            val stored = memory.copy(id = nextId++)
            memories.value = memories.value + stored
            return stored.id
        }

        override suspend fun update(
            memoryId: Long,
            title: String,
            content: String,
            source: String,
            updatedAt: Long
        ) {
            memories.value = memories.value.map { memory ->
                if (memory.id == memoryId) {
                    memory.copy(
                        title = title,
                        content = content,
                        source = source,
                        updatedAt = updatedAt
                    )
                } else {
                    memory
                }
            }
        }

        override suspend fun deleteById(memoryId: Long) {
            memories.value = memories.value.filterNot { it.id == memoryId }
        }

        override suspend fun deleteAll() {
            memories.value = emptyList()
        }
    }
}

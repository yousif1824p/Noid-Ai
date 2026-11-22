package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryRetrievalBoundsTest {
    @Test
    fun requestsOnlyTheConfiguredMaximumCandidateWindow() = runBlocking {
        val dao = RecordingMemoryDao(emptyList())
        val repository = MemoryRepository(dao)

        repository.relevantContext("استعادة الخطة")

        assertEquals(MemoryRepository.MAX_RETRIEVAL_CANDIDATES, dao.lastRecentLimit)
    }

    @Test
    fun equalScoresUseUpdatedTimeThenMemoryIdAsStableTieBreakers() = runBlocking {
        val memories = listOf(
            memory(id = 9, updatedAt = 100),
            memory(id = 2, updatedAt = 200),
            memory(id = 7, updatedAt = 200)
        )
        val repository = MemoryRepository(RecordingMemoryDao(memories))

        val result = repository.relevantContext("استعادة الخطة", maxItems = 3)

        assertEquals(listOf(7L, 2L, 9L), result.map { it.id })
    }

    private fun memory(id: Long, updatedAt: Long) = UserMemory(
        id = id,
        title = "استعادة الخطة",
        content = "استعادة الخطة بعد إغلاق التطبيق",
        source = "موافقة المستخدم",
        createdAt = updatedAt,
        updatedAt = updatedAt
    )

    private class RecordingMemoryDao(
        private val records: List<UserMemory>
    ) : UserMemoryDao {
        var lastRecentLimit: Int? = null

        override fun observeAll(): Flow<List<UserMemory>> = flowOf(records)
        override suspend fun getAll(): List<UserMemory> = records
        override suspend fun getRecent(limit: Int): List<UserMemory> {
            lastRecentLimit = limit
            return records.take(limit)
        }
        override suspend fun search(query: String, limit: Int): List<UserMemory> =
            emptyList()
        override suspend fun insert(memory: UserMemory): Long =
            throw UnsupportedOperationException("Read-only test DAO")
        override suspend fun update(
            memoryId: Long,
            title: String,
            content: String,
            source: String,
            updatedAt: Long
        ) = throw UnsupportedOperationException("Read-only test DAO")
        override suspend fun deleteById(memoryId: Long) =
            throw UnsupportedOperationException("Read-only test DAO")
        override suspend fun deleteAll() =
            throw UnsupportedOperationException("Read-only test DAO")
    }
}

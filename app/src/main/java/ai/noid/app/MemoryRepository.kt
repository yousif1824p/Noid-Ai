package ai.noid.app

import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for memories the user has explicitly approved.
 * This class deliberately has no automatic conversation-to-memory path.
 */
class MemoryRepository(
    private val memoryDao: UserMemoryDao
) {
    val memories: Flow<List<UserMemory>> = memoryDao.observeAll()

    suspend fun saveApprovedMemory(
        title: String,
        content: String,
        source: String
    ): Long {
        val safeTitle = title.trim()
        val safeContent = content.trim()
        val safeSource = source.trim()

        require(safeTitle.isNotEmpty()) { "Memory title cannot be blank." }
        require(safeContent.isNotEmpty()) { "Memory content cannot be blank." }
        require(safeSource.isNotEmpty()) { "Memory source cannot be blank." }

        return memoryDao.insert(
            UserMemory(
                title = safeTitle,
                content = safeContent,
                source = safeSource
            )
        )
    }

    suspend fun searchMemories(query: String, limit: Int = MAX_SEARCH_RESULTS): List<UserMemory> {
        val normalized = query.trim().take(MAX_SEARCH_QUERY_LENGTH)
        if (normalized.isEmpty()) return emptyList()
        return memoryDao.search(normalized, limit.coerceIn(1, MAX_SEARCH_RESULTS))
    }

    /**
     * Retrieves approved memories relevant to the current user request.
     * Candidates are bounded before scoring; no unapproved chat content is indexed.
     */
    suspend fun relevantContext(
        query: String,
        maxItems: Int = MAX_CONTEXT_MEMORIES,
        maxCharacters: Int = MAX_CONTEXT_CHARACTERS
    ): List<UserMemory> {
        require(maxItems in 1..MAX_CONTEXT_MEMORIES) { "Context item limit is out of range." }
        require(maxCharacters in 1..MAX_CONTEXT_CHARACTERS) { "Context character limit is out of range." }
        val tokens = MemoryRetrievalPolicy.tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val candidates = memoryDao.getRecent(MAX_RETRIEVAL_CANDIDATES)
        val ranked = candidates
            .mapNotNull { memory ->
                val score = MemoryRetrievalPolicy.score(query, tokens, memory, candidates)
                if (score > 0.0) memory to score else null
            }
            .sortedWith(
                compareByDescending<Pair<UserMemory, Double>> { it.second }
                    .thenByDescending { it.first.updatedAt }
                    .thenByDescending { it.first.id }
            )
            .take(maxItems)
            .map { it.first }
        return boundedContext(ranked, maxItems, maxCharacters)
    }

    private fun boundedContext(
        memories: List<UserMemory>,
        maxItems: Int,
        maxCharacters: Int
    ): List<UserMemory> {
        var remaining = maxCharacters
        return buildList {
            for (memory in memories.take(maxItems)) {
                if (remaining <= 1) break
                val title = memory.title.take(minOf(memory.title.length, maxOf(1, remaining / 4)))
                remaining -= title.length
                if (remaining <= 0) break
                val content = memory.content.take(remaining)
                remaining -= content.length
                if (content.isBlank()) break
                val source = memory.source.take(remaining)
                remaining -= source.length
                add(memory.copy(title = title, content = content, source = source))
            }
        }
    }

    /**
     * Builds a bounded context from the newest user-approved memories.
     * The returned copies are truncated for this request only; stored memories are unchanged.
     */
    suspend fun recentContext(
        maxItems: Int = MAX_CONTEXT_MEMORIES,
        maxCharacters: Int = MAX_CONTEXT_CHARACTERS
    ): List<UserMemory> {
        require(maxItems in 1..MAX_CONTEXT_MEMORIES) { "Context item limit is out of range." }
        require(maxCharacters in 1..MAX_CONTEXT_CHARACTERS) { "Context character limit is out of range." }

        return boundedContext(memoryDao.getRecent(maxItems), maxItems, maxCharacters)
    }

    suspend fun updateMemory(
        memoryId: Long,
        title: String,
        content: String,
        source: String
    ) {
        require(memoryId > 0) { "Memory id must be positive." }
        val safeTitle = title.trim()
        val safeContent = content.trim()
        val safeSource = source.trim()

        require(safeTitle.isNotEmpty()) { "Memory title cannot be blank." }
        require(safeContent.isNotEmpty()) { "Memory content cannot be blank." }
        require(safeSource.isNotEmpty()) { "Memory source cannot be blank." }

        memoryDao.update(
            memoryId = memoryId,
            title = safeTitle,
            content = safeContent,
            source = safeSource,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun deleteMemory(memoryId: Long) {
        require(memoryId > 0) { "Memory id must be positive." }
        memoryDao.deleteById(memoryId)
    }

    suspend fun deleteAllMemories() {
        memoryDao.deleteAll()
    }

    companion object {
        const val MAX_RETRIEVAL_CANDIDATES = 200
        const val MAX_SEARCH_QUERY_LENGTH = 120
        const val MAX_SEARCH_RESULTS = 100
        const val MAX_CONTEXT_MEMORIES = 20
        const val MAX_CONTEXT_CHARACTERS = 12_000
    }
}

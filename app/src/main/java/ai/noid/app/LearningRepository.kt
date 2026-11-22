package ai.noid.app

import kotlinx.coroutines.flow.Flow

/**
 * User-reviewed knowledge curation. No chat is ingested automatically and no
 * model weights are trained; only approved records enter bounded local context.
 */
class LearningRepository(
    private val dao: LearningRecordDao
) {
    val pending: Flow<List<LearningRecord>> = dao.observePending()

    suspend fun propose(title: String, content: String, source: String): Long {
        val record = validatedRecord(title, content, source)
        return dao.insert(record)
    }

    /**
     * Atomically creates at most one proposal for a stable workflow key.
     * Returns null when that workflow has already produced a proposal,
     * whether the existing proposal is pending, approved, or rejected.
     */
    suspend fun proposeOnce(
        title: String,
        content: String,
        source: String,
        deduplicationKey: String
    ): Long? {
        val key = deduplicationKey.trim()
        require(key.isNotEmpty()) { "مفتاح منع التكرار مطلوب." }
        require(key.length <= MAX_DEDUPLICATION_KEY_CHARS) { "مفتاح منع التكرار طويل جداً." }
        return dao.insertIfAbsent(
            validatedRecord(title, content, source).copy(deduplicationKey = key)
        ).takeIf { it > 0 }
    }

    private fun validatedRecord(title: String, content: String, source: String): LearningRecord {
        val safeTitle = title.trim()
        val safeContent = content.trim()
        val safeSource = source.trim()
        require(safeTitle.isNotEmpty()) { "عنوان المعرفة مطلوب." }
        require(safeContent.isNotEmpty()) { "محتوى المعرفة مطلوب." }
        require(safeSource.isNotEmpty()) { "مصدر المعرفة مطلوب." }
        require(safeTitle.length <= MAX_TITLE_CHARS) { "عنوان المعرفة طويل جداً." }
        require(safeContent.length <= MAX_CONTENT_CHARS) { "محتوى المعرفة طويل جداً." }
        require(safeSource.length <= MAX_SOURCE_CHARS) { "مصدر المعرفة طويل جداً." }
        return LearningRecord(
            title = safeTitle,
            content = safeContent,
            source = safeSource,
            status = LearningRecord.STATUS_PENDING
        )
    }

    suspend fun approve(id: Long): Boolean {
        require(id > 0) { "معرّف المعرفة غير صالح." }
        return dao.approvePending(id, System.currentTimeMillis()) == 1
    }

    suspend fun reject(id: Long): Boolean {
        require(id > 0) { "معرّف المعرفة غير صالح." }
        return dao.rejectPending(id, System.currentTimeMillis()) == 1
    }

    suspend fun approvedContext(
        maxItems: Int = MAX_CONTEXT_ITEMS,
        maxCharacters: Int = MAX_CONTEXT_CHARACTERS
    ): List<LearningRecord> {
        require(maxItems in 1..MAX_CONTEXT_ITEMS)
        require(maxCharacters in 1..MAX_CONTEXT_CHARACTERS)
        var remaining = maxCharacters
        return buildList {
            for (record in dao.getApprovedRecent(maxItems)) {
                if (remaining <= 0) break
                val title = record.title.take(minOf(record.title.length, remaining))
                remaining -= title.length
                if (remaining <= 0) break
                val content = record.content.take(remaining)
                remaining -= content.length
                if (content.isNotBlank()) add(record.copy(title = title, content = content))
            }
        }
    }

    suspend fun searchApproved(query: String, limit: Int = MAX_SEARCH_RESULTS): List<LearningRecord> {
        val normalized = query.trim().take(MAX_SEARCH_QUERY_CHARS)
        if (normalized.isEmpty()) return emptyList()
        return dao.searchApproved(normalized, limit.coerceIn(1, MAX_SEARCH_RESULTS))
    }

    companion object {
        const val MAX_TITLE_CHARS = 160
        const val MAX_CONTENT_CHARS = 8_000
        const val MAX_SOURCE_CHARS = 500
        const val MAX_DEDUPLICATION_KEY_CHARS = 120
        const val MAX_CONTEXT_ITEMS = 12
        const val MAX_CONTEXT_CHARACTERS = 6_000
        const val MAX_SEARCH_QUERY_CHARS = 120
        const val MAX_SEARCH_RESULTS = 50
    }
}

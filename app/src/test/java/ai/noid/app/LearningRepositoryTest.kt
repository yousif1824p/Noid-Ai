package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LearningRepositoryTest {
    @Test
    fun proposalStaysPendingUntilExplicitApproval() = runBlocking {
        val dao = FakeLearningRecordDao()
        val repository = LearningRepository(dao)
        val id = repository.propose(" Kotlin ", " Coroutines ", " جلسة تعليمية ")

        assertEquals(LearningRecord.STATUS_PENDING, dao.records.value.single().status)
        assertTrue(repository.approvedContext().isEmpty())
        assertTrue(repository.approve(id))
        assertEquals(LearningRecord.STATUS_APPROVED, dao.records.value.single().status)
        assertEquals("Kotlin", repository.approvedContext().single().title)
    }

    @Test
    fun rejectedProposalNeverEntersApprovedContext() = runBlocking {
        val dao = FakeLearningRecordDao()
        val repository = LearningRepository(dao)
        val id = repository.propose("درس", "محتوى", "مصدر")

        assertTrue(repository.reject(id))
        assertFalse(repository.approve(id))
        assertTrue(repository.approvedContext().isEmpty())
        assertEquals(LearningRecord.STATUS_REJECTED, dao.records.value.single().status)
    }

    @Test
    fun boundsFieldsAndApprovedContext() = runBlocking {
        val dao = FakeLearningRecordDao()
        val repository = LearningRepository(dao)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.propose("عنوان", "محتوى", " ") }
        }
        val id = repository.propose("عنوان", "x".repeat(8_000), "مصدر")
        repository.approve(id)
        val context = repository.approvedContext(maxItems = 2, maxCharacters = 100)
        assertTrue(context.sumOf { it.title.length + it.content.length } <= 100)
        assertEquals(1, context.size)
    }

    private class FakeLearningRecordDao : LearningRecordDao {
        val records = MutableStateFlow<List<LearningRecord>>(emptyList())
        private var nextId = 1L

        override fun observePending(): Flow<List<LearningRecord>> =
            MutableStateFlow(records.value.filter { it.status == LearningRecord.STATUS_PENDING })

        override suspend fun getApprovedRecent(limit: Int): List<LearningRecord> =
            records.value.filter { it.status == LearningRecord.STATUS_APPROVED }
                .sortedByDescending { it.reviewedAt }.take(limit)

        override suspend fun searchApproved(query: String, limit: Int): List<LearningRecord> =
            records.value.filter {
                it.status == LearningRecord.STATUS_APPROVED &&
                    (it.title.contains(query, true) || it.content.contains(query, true))
            }.take(limit)

        override suspend fun insert(record: LearningRecord): Long {
            val id = nextId++
            records.value = records.value + record.copy(id = id)
            return id
        }

        override suspend fun approvePending(id: Long, reviewedAt: Long): Int =
            transition(id, LearningRecord.STATUS_APPROVED, reviewedAt)

        override suspend fun rejectPending(id: Long, reviewedAt: Long): Int =
            transition(id, LearningRecord.STATUS_REJECTED, reviewedAt)

        private fun transition(id: Long, status: String, reviewedAt: Long): Int {
            var changed = 0
            records.value = records.value.map {
                if (it.id == id && it.status == LearningRecord.STATUS_PENDING) {
                    changed++
                    it.copy(status = status, reviewedAt = reviewedAt)
                } else it
            }
            return changed
        }

        override suspend fun purgeRejected(olderThan: Long): Int {
            val oldSize = records.value.size
            records.value = records.value.filterNot {
                it.status == LearningRecord.STATUS_REJECTED &&
                    (it.reviewedAt ?: Long.MAX_VALUE) < olderThan
            }
            return oldSize - records.value.size
        }
    }
}

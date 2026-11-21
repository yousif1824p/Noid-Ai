package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfDevelopmentProposalRepositoryTest {
    private val testTreeUri = "content://com.example.documents/tree/project"
    @Test
    fun proposalIsStoredPendingAndDoesNotChangeTheSourceSnapshot() = runBlocking {
        val dao = FakeDao()
        val repository = SelfDevelopmentProposalRepository(dao)
        val original = "class A { val value = 1 }"
        val proposed = "class A { val value = 2 }"

        val id = repository.propose(
            "تحسين قيمة",
            "تصحيح القيمة الافتراضية",
            "src/A.kt",
            original,
            proposed,
            testTreeUri
        )

        val stored = dao.rows.value.single()
        assertEquals(id, stored.id)
        assertEquals(SelfDevelopmentProposal.STATUS_PENDING_REVIEW, stored.status)
        assertEquals(original, stored.originalContent)
        assertEquals(proposed, stored.proposedContent)
        assertEquals(SelfDevelopmentProposalRepository.sha256(original), stored.originalSha256)
        assertEquals(SelfDevelopmentProposalRepository.sha256(proposed), stored.proposedSha256)
        assertNotEquals(stored.originalSha256, stored.proposedSha256)
    }

    @Test
    fun onlyPendingProposalCanTransitionToApprovedOrRejected() = runBlocking {
        val dao = FakeDao()
        val repository = SelfDevelopmentProposalRepository(dao)
        val first = repository.propose("تغيير 1", "سبب", "src/A.kt", "a", "b", testTreeUri)
        val second = repository.propose("تغيير 2", "سبب", "src/B.kt", "c", "d", testTreeUri)

        assertTrue(repository.approve(first))
        assertFalse(repository.reject(first))
        assertTrue(repository.reject(second))
        assertFalse(repository.approve(second))
        assertEquals(
            listOf(SelfDevelopmentProposal.STATUS_APPROVED, SelfDevelopmentProposal.STATUS_REJECTED),
            dao.rows.value.sortedBy { it.id }.map { it.status }
        )
    }

    @Test
    fun rejectsUnsafePathsUnsupportedFilesUnchangedContentAndOversizedInput(): Unit = runBlocking {
        val repository = SelfDevelopmentProposalRepository(FakeDao())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.propose("title", "reason", "../secret.kt", "a", "b", testTreeUri) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.propose("title", "reason", "/absolute.kt", "a", "b", testTreeUri) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.propose("title", "reason", "assets/model.bin", "a", "b", testTreeUri) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.propose("title", "reason", "src/A.kt", "same", "same", testTreeUri) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.propose(
                    "title",
                    "reason",
                    "src/A.kt",
                    "a",
                    "x".repeat(SelfDevelopmentProposalRepository.MAX_CONTENT_BYTES + 1),
                    testTreeUri
                )
            }
        }
    }

    @Test
    fun rejectsInvalidReviewIdsAndBoundsRecentResults(): Unit = runBlocking {
        val dao = FakeDao()
        val repository = SelfDevelopmentProposalRepository(dao)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.approve(0) } }
        repeat(4) { index ->
            repository.propose("title $index", "reason", "src/$index.kt", "a", "b$index", testTreeUri)
        }
        assertEquals(2, repository.recent(2).size)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.recent(101) } }
    }

    private class FakeDao : SelfDevelopmentProposalDao {
        val rows = MutableStateFlow<List<SelfDevelopmentProposal>>(emptyList())
        private var nextId = 1L

        override fun observePending(): Flow<List<SelfDevelopmentProposal>> =
            rows.map { it.filter { row -> row.status == SelfDevelopmentProposal.STATUS_PENDING_REVIEW } }

        override suspend fun getRecent(limit: Int): List<SelfDevelopmentProposal> =
            rows.value.sortedByDescending { it.createdAt }.take(limit)

        override suspend fun getById(id: Long): SelfDevelopmentProposal? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun insert(proposal: SelfDevelopmentProposal): Long {
            val id = nextId++
            rows.value = rows.value + proposal.copy(id = id)
            return id
        }

        override suspend fun approvePending(id: Long, reviewedAt: Long): Int =
            transition(id, SelfDevelopmentProposal.STATUS_APPROVED, reviewedAt)

        override suspend fun rejectPending(id: Long, reviewedAt: Long): Int =
            transition(id, SelfDevelopmentProposal.STATUS_REJECTED, reviewedAt)

        override suspend fun claimApprovedForApply(id: Long, reviewedAt: Long): Int =
            transitionFrom(id, SelfDevelopmentProposal.STATUS_APPROVED, SelfDevelopmentProposal.STATUS_APPLYING, reviewedAt)

        override suspend fun markApplied(id: Long, reviewedAt: Long): Int =
            transitionFrom(id, SelfDevelopmentProposal.STATUS_APPLYING, SelfDevelopmentProposal.STATUS_APPLIED, reviewedAt)

        override suspend fun markStale(id: Long, reviewedAt: Long): Int =
            transitionFrom(id, SelfDevelopmentProposal.STATUS_APPLYING, SelfDevelopmentProposal.STATUS_STALE, reviewedAt)

        override suspend fun markApplyFailed(id: Long, reviewedAt: Long): Int =
            transitionFrom(id, SelfDevelopmentProposal.STATUS_APPLYING, SelfDevelopmentProposal.STATUS_APPLY_FAILED, reviewedAt)

        override suspend fun markApplyInterrupted(id: Long, reviewedAt: Long): Int =
            transitionFrom(id, SelfDevelopmentProposal.STATUS_APPLYING, SelfDevelopmentProposal.STATUS_APPLY_INTERRUPTED, reviewedAt)

        override suspend fun releaseApplyClaim(id: Long): Int =
            transitionFrom(id, SelfDevelopmentProposal.STATUS_APPLYING, SelfDevelopmentProposal.STATUS_APPROVED, null)

        private fun transitionFrom(id: Long, from: String, to: String, reviewedAt: Long?): Int {
            var changed = 0
            rows.value = rows.value.map { row ->
                if (row.id == id && row.status == from) {
                    changed++
                    row.copy(status = to, reviewedAt = reviewedAt ?: row.reviewedAt)
                } else row
            }
            return changed
        }

        private fun transition(id: Long, status: String, reviewedAt: Long): Int {
            var changed = 0
            rows.value = rows.value.map { row ->
                if (row.id == id && row.status == SelfDevelopmentProposal.STATUS_PENDING_REVIEW) {
                    changed++
                    row.copy(status = status, reviewedAt = reviewedAt)
                } else row
            }
            return changed
        }
    }
}

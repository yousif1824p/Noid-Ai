package ai.noid.app

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelfDevelopmentProposalDaoTest {
    @Test
    fun applyClaimIsSingleUseAndTerminalStatesCannotBeReplayed() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            NoidDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val dao = database.selfDevelopmentProposalDao()
            val id = dao.insert(
                SelfDevelopmentProposal(
                    title = "تطبيق آمن",
                    rationale = "اختبار الانتقال الذري",
                    relativePath = "src/A.kt",
                    originalContent = "before",
                    proposedContent = "after",
                    originalSha256 = SelfDevelopmentProposalRepository.sha256("before"),
                    proposedSha256 = SelfDevelopmentProposalRepository.sha256("after")
                )
            )
            assertEquals(1, dao.approvePending(id, 10L))
            assertEquals(1, dao.claimApprovedForApply(id, 20L))
            assertEquals(0, dao.claimApprovedForApply(id, 21L))
            assertEquals(SelfDevelopmentProposal.STATUS_APPLYING, dao.getById(id)?.status)
            assertEquals(1, dao.markApplied(id, 30L))
            assertEquals(0, dao.markApplyFailed(id, 31L))
            assertEquals(0, dao.claimApprovedForApply(id, 32L))
            assertEquals(SelfDevelopmentProposal.STATUS_APPLIED, dao.getById(id)?.status)

            val staleId = dao.insert(
                SelfDevelopmentProposal(
                    title = "مصدر قديم",
                    rationale = "اختبار الرفض",
                    relativePath = "src/B.kt",
                    originalContent = "old",
                    proposedContent = "new",
                    originalSha256 = SelfDevelopmentProposalRepository.sha256("old"),
                    proposedSha256 = SelfDevelopmentProposalRepository.sha256("new")
                )
            )
            assertEquals(1, dao.approvePending(staleId, 40L))
            assertEquals(1, dao.claimApprovedForApply(staleId, 41L))
            assertEquals(1, dao.markStale(staleId, 42L))
            assertEquals(0, dao.markApplied(staleId, 43L))
            assertEquals(SelfDevelopmentProposal.STATUS_STALE, dao.getById(staleId)?.status)

            val retryId = dao.insert(
                SelfDevelopmentProposal(
                    title = "إعادة محاولة",
                    rationale = "إذن مؤقت",
                    relativePath = "src/C.kt",
                    originalContent = "a",
                    proposedContent = "b",
                    originalSha256 = SelfDevelopmentProposalRepository.sha256("a"),
                    proposedSha256 = SelfDevelopmentProposalRepository.sha256("b")
                )
            )
            assertEquals(1, dao.approvePending(retryId, 50L))
            assertEquals(1, dao.claimApprovedForApply(retryId, 51L))
            assertEquals(1, dao.releaseApplyClaim(retryId))
            assertEquals(SelfDevelopmentProposal.STATUS_APPROVED, dao.getById(retryId)?.status)
            assertEquals(1, dao.claimApprovedForApply(retryId, 52L))
            assertEquals(1, dao.markApplyInterrupted(retryId, 53L))
            assertEquals(0, dao.releaseApplyClaim(retryId))
            assertEquals(SelfDevelopmentProposal.STATUS_APPLY_INTERRUPTED, dao.getById(retryId)?.status)
        } finally {
            database.close()
        }
    }

    @Test
    fun roomPersistsProposalAndEnforcesSinglePendingReviewTransition() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            NoidDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val dao = database.selfDevelopmentProposalDao()
            val id = dao.insert(
                SelfDevelopmentProposal(
                    title = "تعديل",
                    rationale = "اختبار",
                    relativePath = "src/A.kt",
                    originalContent = "before",
                    proposedContent = "after",
                    originalSha256 = SelfDevelopmentProposalRepository.sha256("before"),
                    proposedSha256 = SelfDevelopmentProposalRepository.sha256("after")
                )
            )
            assertEquals(1, dao.observePending().first().size)
            assertEquals(1, dao.approvePending(id, 100L))
            assertEquals(0, dao.rejectPending(id, 101L))
            assertTrue(dao.observePending().first().isEmpty())
            assertEquals(SelfDevelopmentProposal.STATUS_APPROVED, dao.getById(id)?.status)

            val rejectedId = dao.insert(
                SelfDevelopmentProposal(
                    title = "رفض",
                    rationale = "اختبار",
                    relativePath = "src/B.kt",
                    originalContent = "x",
                    proposedContent = "y",
                    originalSha256 = SelfDevelopmentProposalRepository.sha256("x"),
                    proposedSha256 = SelfDevelopmentProposalRepository.sha256("y")
                )
            )
            assertEquals(1, dao.rejectPending(rejectedId, 102L))
            assertFalse(dao.observePending().first().any { it.id == rejectedId })
        } finally {
            database.close()
        }
    }
}

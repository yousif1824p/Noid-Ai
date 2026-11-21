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

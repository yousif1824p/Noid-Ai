package ai.noid.app

import android.os.Looper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SelfDevelopmentReviewViewModelTest {
    @Test
    fun approvalChangesOnlyProposalStatusAndExplainsThatNoFileWasApplied() {
        val dao = FakeProposalDao()
        val repository = SelfDevelopmentProposalRepository(dao)
        val id = runSuspend { repository.propose("تعديل", "سبب", "src/A.kt", "old", "new") }
        val viewModel = SelfDevelopmentReviewViewModel(repository)
        idleMainLooper()

        viewModel.approve(id)
        idleMainLooper()

        assertEquals(SelfDevelopmentProposal.STATUS_APPROVED, dao.rows.value.single().status)
        assertEquals("old", dao.rows.value.single().originalContent)
        assertEquals("new", dao.rows.value.single().proposedContent)
        assertTrue(viewModel.message.value!!.contains("لم يتغير أي ملف"))
        assertFalse(viewModel.busy.value)
    }

    @Test
    fun rejectionAndFailureAreReportedWithoutExposingInternalDetails() {
        val dao = FakeProposalDao()
        val repository = SelfDevelopmentProposalRepository(dao)
        val id = runSuspend { repository.propose("تعديل", "سبب", "src/A.kt", "old", "new") }
        val viewModel = SelfDevelopmentReviewViewModel(repository)
        idleMainLooper()

        viewModel.reject(id)
        idleMainLooper()
        assertEquals(SelfDevelopmentProposal.STATUS_REJECTED, dao.rows.value.single().status)
        assertEquals("رُفض المقترح ولم يُطبّق أي تغيير.", viewModel.message.value)

        val failingDao = FakeProposalDao().apply { failReview = true }
        val failingRepository = SelfDevelopmentProposalRepository(failingDao)
        val failingId = runSuspend { failingRepository.propose("تعديل", "سبب", "src/A.kt", "old", "new") }
        val failingViewModel = SelfDevelopmentReviewViewModel(failingRepository)
        idleMainLooper()
        failingViewModel.approve(failingId)
        idleMainLooper()
        assertEquals("تعذرت الموافقة؛ ربما عولج المقترح مسبقاً.", failingViewModel.message.value)
        assertFalse(failingViewModel.busy.value)
    }

    private fun idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun runSuspend(block: suspend () -> Long): Long {
        var result = 0L
        kotlinx.coroutines.runBlocking { result = block() }
        return result
    }

    private class FakeProposalDao : SelfDevelopmentProposalDao {
        val rows = MutableStateFlow<List<SelfDevelopmentProposal>>(emptyList())
        private var nextId = 1L
        var failReview = false

        override fun observePending(): Flow<List<SelfDevelopmentProposal>> =
            rows.map { list -> list.filter { it.status == SelfDevelopmentProposal.STATUS_PENDING_REVIEW } }

        override suspend fun getRecent(limit: Int): List<SelfDevelopmentProposal> =
            rows.value.sortedByDescending { it.createdAt }.take(limit)

        override suspend fun getById(id: Long): SelfDevelopmentProposal? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun insert(proposal: SelfDevelopmentProposal): Long {
            val id = nextId++
            rows.value = rows.value + proposal.copy(id = id)
            return id
        }

        override suspend fun approvePending(id: Long, reviewedAt: Long): Int {
            if (failReview) throw IllegalStateException("private database detail")
            return transition(id, SelfDevelopmentProposal.STATUS_APPROVED, reviewedAt)
        }

        override suspend fun rejectPending(id: Long, reviewedAt: Long): Int =
            transition(id, SelfDevelopmentProposal.STATUS_REJECTED, reviewedAt)

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

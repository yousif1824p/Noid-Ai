package ai.noid.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SelfDevelopmentReviewScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun screenShowsProposalDetailsAndClearlySeparatesApprovalFromApplying() {
        val dao = FakeProposalDao()
        runBlocking {
            dao.insert(
                SelfDevelopmentProposal(
                    title = "تحسين المعالجة",
                    rationale = "إصلاح حالة حدية",
                    relativePath = "src/Worker.kt",
                    originalContent = "val count = 1",
                    proposedContent = "val count = 2",
                    originalSha256 = SelfDevelopmentProposalRepository.sha256("val count = 1"),
                    proposedSha256 = SelfDevelopmentProposalRepository.sha256("val count = 2")
                )
            )
        }
        val viewModel = SelfDevelopmentReviewViewModel(SelfDevelopmentProposalRepository(dao))

        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        composeRule.setContent {
            MaterialTheme { SelfDevelopmentReviewScreen(viewModel) }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("مراجعة تطوير NOID").fetchSemanticsNode()
        composeRule.onNodeWithText("تحسين المعالجة").fetchSemanticsNode()
        composeRule.onNodeWithText("المسار: src/Worker.kt").fetchSemanticsNode()
        composeRule.onNodeWithText("موافقة للمراجعة اللاحقة").fetchSemanticsNode()
        composeRule.onNodeWithText(
            "تُحفظ المقترحات منفصلة عن ملفات المشروع. الموافقة وحدها لا تكتب الملفات؛ يُتاح التطبيق لاحقاً بعد عرض المقارنة الكاملة وتأكيد مستقل وإعادة فحص المصدر."
        ).fetchSemanticsNode()
        assertEquals(SelfDevelopmentProposal.STATUS_PENDING_REVIEW, dao.rows.value.single().status)
    }

    private class FakeProposalDao : SelfDevelopmentProposalDao {
        val rows = MutableStateFlow<List<SelfDevelopmentProposal>>(emptyList())
        override fun observePending(): Flow<List<SelfDevelopmentProposal>> =
            rows.map { list -> list.filter { it.status == SelfDevelopmentProposal.STATUS_PENDING_REVIEW } }
        override suspend fun getRecent(limit: Int): List<SelfDevelopmentProposal> =
            rows.value.sortedByDescending { it.createdAt }.take(limit)
        override suspend fun getById(id: Long): SelfDevelopmentProposal? =
            rows.value.firstOrNull { it.id == id }
        override suspend fun insert(proposal: SelfDevelopmentProposal): Long {
            val id = (rows.value.maxOfOrNull { it.id } ?: 0L) + 1L
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

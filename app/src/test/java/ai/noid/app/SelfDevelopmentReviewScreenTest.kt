package ai.noid.app

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

        composeRule.setContent {
            MaterialTheme { SelfDevelopmentReviewScreen(viewModel) }
        }

        // The screen loads recent proposals asynchronously in init. Wait for the
        // repository result before asserting card semantics; otherwise Robolectric
        // can observe the initial empty StateFlow and time out on the title.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.recent.value.singleOrNull()?.title == "تحسين المعالجة"
        }
        composeRule.onNodeWithText("مراجعة تطوير NOID").fetchSemanticsNode()
        composeRule.onNodeWithText("تحسين المعالجة").fetchSemanticsNode()
        composeRule.onNodeWithText("المسار: src/Worker.kt").fetchSemanticsNode()
        composeRule.onNodeWithText("موافقة للمراجعة اللاحقة").fetchSemanticsNode()
        composeRule.onNodeWithText(
            "هذه مقترحات محفوظة منفصلة عن ملفات المشروع. الموافقة تسجل قرارك فقط؛ لا تكتب الملفات ولا تمنح إذناً بالتطبيق. لن يُنفّذ أي تغيير من هذه الشاشة."
        ).fetchSemanticsNode()
        composeRule.onNodeWithText("موافقة للمراجعة اللاحقة").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            dao.rows.value.singleOrNull()?.status == SelfDevelopmentProposal.STATUS_APPROVED
        }
        assertEquals(SelfDevelopmentProposal.STATUS_APPROVED, dao.rows.value.single().status)
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

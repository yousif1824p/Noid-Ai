package ai.noid.app

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LearningRecordDaoTest {
    @Test
    fun onlyExplicitlyApprovedRecordsAreReturnedAsContext() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            NoidDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val dao = database.learningRecordDao()
            val pendingId = dao.insert(
                LearningRecord(title = "قواعد Kotlin", content = "المحتوى", source = "اختبار")
            )
            assertEquals(1, dao.observePending().first().size)
            assertTrue(dao.getApprovedRecent(10).isEmpty())

            assertEquals(1, dao.approvePending(pendingId, 100L))
            assertTrue(dao.observePending().first().isEmpty())
            assertEquals(pendingId, dao.getApprovedRecent(10).single().id)

            val rejectedId = dao.insert(
                LearningRecord(title = "مرفوض", content = "لا يستخدم", source = "اختبار")
            )
            assertEquals(1, dao.rejectPending(rejectedId, 101L))
            assertEquals(listOf(pendingId), dao.getApprovedRecent(10).map { it.id })
        } finally {
            database.close()
        }
    }
}

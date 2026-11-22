package ai.noid.app

import androidx.room.Room
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
class TaskPlanRecoveryStoreTest {
    @Test fun approvalWaitIsInterruptedOnRestart() = runBlocking {
        val db = database()
        try {
            val store = TaskPlanRecoveryStore(db.taskPlanRecoveryDao())
            assertTrue(store.create(plan("approval")))
            assertTrue(store.markAwaitingApproval("approval", 0, "step-a"))
            TaskPlanRecoveryStore(db.taskPlanRecoveryDao()).unresolved()
            val record = db.taskPlanRecoveryDao().find("approval")!!
            assertEquals(TaskPlanRecoveryRecord.INTERRUPTED, record.state)
            assertFalse(record.dispatchStarted)
        } finally { db.close() }
    }

    @Test fun uncertainDispatchRequiresReviewAndCannotBeDispatchedAgain() = runBlocking {
        val db = database()
        try {
            val store = TaskPlanRecoveryStore(db.taskPlanRecoveryDao())
            assertTrue(store.create(plan("uncertain")))
            assertTrue(store.markDispatching("uncertain", 0, "write"))
            assertEquals(1, TaskPlanRecoveryStore(db.taskPlanRecoveryDao()).unresolved().size)
            assertEquals(TaskPlanRecoveryRecord.REVIEW_REQUIRED, db.taskPlanRecoveryDao().find("uncertain")!!.state)
            assertFalse(store.markDispatching("uncertain", 0, "write"))
        } finally { db.close() }
    }

    @Test fun terminalPlanIsNotChangedByRecovery() = runBlocking {
        val db = database()
        try {
            val store = TaskPlanRecoveryStore(db.taskPlanRecoveryDao())
            assertTrue(store.create(plan("completed")))
            assertTrue(store.markTerminal("completed", TaskPlanRecoveryRecord.COMPLETED))
            assertTrue(TaskPlanRecoveryStore(db.taskPlanRecoveryDao()).unresolved().isEmpty())
            assertEquals(TaskPlanRecoveryRecord.COMPLETED, db.taskPlanRecoveryDao().find("completed")!!.state)
        } finally { db.close() }
    }

    @Test fun oldPlanIdCannotBeReused() = runBlocking {
        val db = database()
        try {
            val store = TaskPlanRecoveryStore(db.taskPlanRecoveryDao())
            assertTrue(store.create(plan("same-id")))
            assertFalse(store.create(plan("same-id")))
        } finally { db.close() }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), NoidDatabase::class.java
    ).allowMainThreadQueries().build()

    private fun plan(id: String) = AssistantTaskPlan(
        id, "اختبار الاستعادة",
        listOf(AssistantTaskPlanStep("step-a", AssistantToolProposal("read", emptyMap())))
    )
}

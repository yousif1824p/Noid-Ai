package ai.noid.app

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "task_plan_recovery")
data class TaskPlanRecoveryRecord(
    @PrimaryKey val planId: String,
    val stepCount: Int,
    val currentStepIndex: Int,
    val currentStepId: String?,
    val state: String,
    val dispatchStarted: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val RUNNING = "RUNNING"
        const val WAITING_APPROVAL = "WAITING_APPROVAL"
        const val COMPLETED = "COMPLETED"
        const val STOPPED = "STOPPED"
        const val CANCELLED = "CANCELLED"
        const val INTERRUPTED = "INTERRUPTED"
        const val REVIEW_REQUIRED = "REVIEW_REQUIRED"
    }
}

@Dao
interface TaskPlanRecoveryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: TaskPlanRecoveryRecord): Long

    @Query("""
        UPDATE task_plan_recovery
        SET state = CASE WHEN dispatchStarted = 1 THEN 'REVIEW_REQUIRED' ELSE 'INTERRUPTED' END,
            dispatchStarted = 0, updatedAt = :now
        WHERE state IN ('RUNNING', 'WAITING_APPROVAL')
    """)
    suspend fun recoverNonTerminal(now: Long): Int

    @Query("""
        UPDATE task_plan_recovery
        SET state = 'RUNNING', currentStepIndex = :index, currentStepId = :stepId,
            dispatchStarted = 1, updatedAt = :now
        WHERE planId = :planId AND state IN ('RUNNING', 'WAITING_APPROVAL')
    """)
    suspend fun markDispatching(planId: String, index: Int, stepId: String, now: Long): Int

    @Query("""
        UPDATE task_plan_recovery
        SET state = 'WAITING_APPROVAL', currentStepIndex = :index, currentStepId = :stepId,
            dispatchStarted = 0, updatedAt = :now
        WHERE planId = :planId AND state = 'RUNNING'
    """)
    suspend fun markAwaitingApproval(planId: String, index: Int, stepId: String, now: Long): Int

    @Query("""
        UPDATE task_plan_recovery
        SET state = 'RUNNING', currentStepIndex = :nextIndex, currentStepId = NULL,
            dispatchStarted = 0, updatedAt = :now
        WHERE planId = :planId AND state = 'RUNNING'
    """)
    suspend fun markStepSucceeded(planId: String, nextIndex: Int, now: Long): Int

    @Query("""
        UPDATE task_plan_recovery
        SET state = CASE WHEN dispatchStarted = 1 THEN 'REVIEW_REQUIRED' ELSE 'INTERRUPTED' END,
            dispatchStarted = 0, updatedAt = :now
        WHERE planId = :planId AND state IN ('RUNNING', 'WAITING_APPROVAL')
    """)
    suspend fun markInterruptedOrReview(planId: String, now: Long): Int

    @Query("""
        UPDATE task_plan_recovery
        SET state = :state, dispatchStarted = 0, updatedAt = :now
        WHERE planId = :planId AND state IN ('RUNNING', 'WAITING_APPROVAL')
    """)
    suspend fun markTerminal(planId: String, state: String, now: Long): Int

    @Query("""
        UPDATE task_plan_recovery
        SET state = CASE WHEN dispatchStarted = 1 THEN 'REVIEW_REQUIRED' ELSE 'STOPPED' END,
            dispatchStarted = 0, updatedAt = :now
        WHERE planId = :planId AND state IN ('RUNNING', 'WAITING_APPROVAL')
    """)
    suspend fun markStoppedOrReview(planId: String, now: Long): Int

    @Query("SELECT * FROM task_plan_recovery WHERE planId = :planId LIMIT 1")
    suspend fun find(planId: String): TaskPlanRecoveryRecord?

    @Query("SELECT * FROM task_plan_recovery WHERE state IN ('INTERRUPTED', 'REVIEW_REQUIRED') ORDER BY updatedAt DESC LIMIT 100")
    suspend fun unresolved(): List<TaskPlanRecoveryRecord>
}

class TaskPlanRecoveryStore(private val dao: TaskPlanRecoveryDao) {
    private val mutex = Mutex()
    @Volatile private var recovered = false

    suspend fun create(plan: AssistantTaskPlan): Boolean {
        ensureRecovered()
        val now = System.currentTimeMillis()
        return dao.insert(TaskPlanRecoveryRecord(
            planId = plan.planId,
            stepCount = plan.steps.size,
            currentStepIndex = 0,
            currentStepId = null,
            state = TaskPlanRecoveryRecord.RUNNING,
            dispatchStarted = false,
            createdAt = now,
            updatedAt = now
        )) > 0
    }

    suspend fun markDispatching(planId: String, index: Int, stepId: String): Boolean =
        dao.markDispatching(planId, index, stepId, System.currentTimeMillis()) == 1

    suspend fun markAwaitingApproval(planId: String, index: Int, stepId: String): Boolean =
        dao.markAwaitingApproval(planId, index, stepId, System.currentTimeMillis()) == 1

    suspend fun markStepSucceeded(planId: String, nextIndex: Int): Boolean =
        dao.markStepSucceeded(planId, nextIndex, System.currentTimeMillis()) == 1

    suspend fun markInterruptedOrReview(planId: String): Boolean =
        dao.markInterruptedOrReview(planId, System.currentTimeMillis()) == 1

    suspend fun markTerminal(planId: String, state: String): Boolean {
        require(state in setOf(TaskPlanRecoveryRecord.COMPLETED, TaskPlanRecoveryRecord.STOPPED, TaskPlanRecoveryRecord.CANCELLED))
        return dao.markTerminal(planId, state, System.currentTimeMillis()) == 1
    }

    /** Preserve uncertainty after dispatch; a failed action may have partially changed external state. */
    suspend fun markStoppedOrReview(planId: String): Boolean =
        dao.markStoppedOrReview(planId, System.currentTimeMillis()) == 1

    suspend fun unresolved(): List<TaskPlanRecoveryRecord> {
        ensureRecovered()
        return dao.unresolved()
    }

    private suspend fun ensureRecovered() {
        if (recovered) return
        mutex.withLock {
            if (!recovered) {
                dao.recoverNonTerminal(System.currentTimeMillis())
                recovered = true
            }
        }
    }
}

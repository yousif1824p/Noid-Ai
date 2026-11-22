package ai.noid.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningRecordDao {
    @Query("SELECT * FROM learning_records WHERE status = 'PENDING' ORDER BY createdAt DESC, id DESC")
    fun observePending(): Flow<List<LearningRecord>>

    @Query("SELECT * FROM learning_records WHERE status = 'APPROVED' ORDER BY reviewedAt DESC, id DESC LIMIT :limit")
    suspend fun getApprovedRecent(limit: Int): List<LearningRecord>

    @Query("SELECT * FROM learning_records WHERE status = 'APPROVED' AND (instr(lower(title), lower(:query)) > 0 OR instr(lower(content), lower(:query)) > 0) ORDER BY reviewedAt DESC, id DESC LIMIT :limit")
    suspend fun searchApproved(query: String, limit: Int): List<LearningRecord>

    @Insert
    suspend fun insert(record: LearningRecord): Long

    /** Returns -1 when the unique key already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(record: LearningRecord): Long

    @Query("UPDATE learning_records SET status = 'APPROVED', reviewedAt = :reviewedAt WHERE id = :id AND status = 'PENDING'")
    suspend fun approvePending(id: Long, reviewedAt: Long): Int

    @Query("UPDATE learning_records SET status = 'REJECTED', reviewedAt = :reviewedAt WHERE id = :id AND status = 'PENDING'")
    suspend fun rejectPending(id: Long, reviewedAt: Long): Int

    @Query("DELETE FROM learning_records WHERE status = 'REJECTED' AND reviewedAt < :olderThan")
    suspend fun purgeRejected(olderThan: Long): Int
}

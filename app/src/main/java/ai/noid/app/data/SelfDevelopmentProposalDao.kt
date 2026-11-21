package ai.noid.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SelfDevelopmentProposalDao {
    @Query("SELECT * FROM self_development_proposals WHERE status = 'PENDING_REVIEW' ORDER BY createdAt DESC, id DESC")
    fun observePending(): Flow<List<SelfDevelopmentProposal>>

    @Query("SELECT * FROM self_development_proposals ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SelfDevelopmentProposal>

    @Query("SELECT * FROM self_development_proposals WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SelfDevelopmentProposal?

    @Insert
    suspend fun insert(proposal: SelfDevelopmentProposal): Long

    @Query("UPDATE self_development_proposals SET status = 'APPROVED', reviewedAt = :reviewedAt WHERE id = :id AND status = 'PENDING_REVIEW'")
    suspend fun approvePending(id: Long, reviewedAt: Long): Int

    @Query("UPDATE self_development_proposals SET status = 'REJECTED', reviewedAt = :reviewedAt WHERE id = :id AND status = 'PENDING_REVIEW'")
    suspend fun rejectPending(id: Long, reviewedAt: Long): Int
}

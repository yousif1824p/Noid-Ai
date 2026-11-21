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

    /** Atomic claim prevents two screens or taps from applying one proposal twice. */
    @Query("UPDATE self_development_proposals SET status = 'APPLYING', reviewedAt = :reviewedAt WHERE id = :id AND status = 'APPROVED'")
    suspend fun claimApprovedForApply(id: Long, reviewedAt: Long): Int

    @Query("UPDATE self_development_proposals SET status = 'APPLIED', reviewedAt = :reviewedAt WHERE id = :id AND status = 'APPLYING'")
    suspend fun markApplied(id: Long, reviewedAt: Long): Int

    @Query("UPDATE self_development_proposals SET status = 'STALE', reviewedAt = :reviewedAt WHERE id = :id AND status = 'APPLYING'")
    suspend fun markStale(id: Long, reviewedAt: Long): Int

    @Query("UPDATE self_development_proposals SET status = 'APPLY_FAILED', reviewedAt = :reviewedAt WHERE id = :id AND status = 'APPLYING'")
    suspend fun markApplyFailed(id: Long, reviewedAt: Long): Int

    @Query("UPDATE self_development_proposals SET status = 'APPLY_INTERRUPTED', reviewedAt = :reviewedAt WHERE id = :id AND status = 'APPLYING'")
    suspend fun markApplyInterrupted(id: Long, reviewedAt: Long): Int

    @Query("UPDATE self_development_proposals SET status = 'APPROVED' WHERE id = :id AND status = 'APPLYING'")
    suspend fun releaseApplyClaim(id: Long): Int
}

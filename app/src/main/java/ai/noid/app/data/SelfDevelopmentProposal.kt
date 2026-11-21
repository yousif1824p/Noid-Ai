package ai.noid.app

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A proposed source change is stored separately from the project tree.
 * Approval records consent to review/apply later; it never writes a file.
 */
@Entity(
    tableName = "self_development_proposals",
    indices = [Index(value = ["status", "createdAt"])]
)
data class SelfDevelopmentProposal(
    val title: String,
    val rationale: String,
    val relativePath: String,
    val originalContent: String,
    val proposedContent: String,
    val originalSha256: String,
    val proposedSha256: String,
    val status: String = STATUS_PENDING_REVIEW,
    val createdAt: Long = System.currentTimeMillis(),
    val reviewedAt: Long? = null,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
) {
    companion object {
        const val STATUS_PENDING_REVIEW = "PENDING_REVIEW"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
    }
}

package ai.noid.app

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * A candidate lesson is inert until the user explicitly approves it.
 * This is curated retrieval data, never model-weight training.
 */
@Entity(tableName = "learning_records", indices = [Index(value = ["status", "reviewedAt"])])
data class LearningRecord(
    val title: String,
    val content: String,
    val source: String,
    val status: String = STATUS_PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val reviewedAt: Long? = null,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
    }
}

package ai.noid.app

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A durable memory explicitly approved by the user.
 * Only approved memories are inserted through MemoryRepository.
 */
@Entity(tableName = "user_memories")
data class UserMemory(
    val title: String,
    val content: String,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)

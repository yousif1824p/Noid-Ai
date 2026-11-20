package ai.noid.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE instr(lower(text), lower(:query)) > 0 ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<ChatMessage>

    @Insert
    suspend fun insert(message: ChatMessage): Long

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}

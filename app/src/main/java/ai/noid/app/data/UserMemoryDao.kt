package ai.noid.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserMemoryDao {
    @Query("SELECT * FROM user_memories ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<UserMemory>>

    @Query("SELECT * FROM user_memories ORDER BY updatedAt DESC, id DESC")
    suspend fun getAll(): List<UserMemory>

    @Query("SELECT * FROM user_memories ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<UserMemory>

    @Query("SELECT * FROM user_memories WHERE instr(lower(title), lower(:query)) > 0 OR instr(lower(content), lower(:query)) > 0 ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<UserMemory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: UserMemory): Long

    @Query("UPDATE user_memories SET title = :title, content = :content, source = :source, updatedAt = :updatedAt WHERE id = :memoryId")
    suspend fun update(memoryId: Long, title: String, content: String, source: String, updatedAt: Long)

    @Query("DELETE FROM user_memories WHERE id = :memoryId")
    suspend fun deleteById(memoryId: Long)

    @Query("DELETE FROM user_memories")
    suspend fun deleteAll()
}

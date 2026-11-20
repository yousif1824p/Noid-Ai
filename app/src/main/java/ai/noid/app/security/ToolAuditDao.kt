package ai.noid.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolAuditDao {
    @Query("SELECT * FROM tool_audit_events ORDER BY createdAt DESC, id DESC")
    fun observeRecent(): Flow<List<ToolAuditEvent>>

    @Insert
    suspend fun insert(event: ToolAuditEvent): Long

}

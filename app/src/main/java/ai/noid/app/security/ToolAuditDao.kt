package ai.noid.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolAuditDao {
    /**
     * Keep the observable UI window bounded. The database remains append-only;
     * this limit affects only the live screen query, not persisted audit history.
     */
    @Query("SELECT * FROM tool_audit_events ORDER BY createdAt DESC, id DESC LIMIT 250")
    fun observeRecent(): Flow<List<ToolAuditEvent>>

    @Insert
    suspend fun insert(event: ToolAuditEvent): Long
}

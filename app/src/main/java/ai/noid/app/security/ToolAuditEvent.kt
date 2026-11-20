package ai.noid.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tool_audit_events")
data class ToolAuditEvent(
    val actionId: String,
    val actionType: String,
    val decision: String,
    val details: String?,
    val createdAt: Long = System.currentTimeMillis(),
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)

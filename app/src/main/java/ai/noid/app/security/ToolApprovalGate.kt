package ai.noid.app

import java.util.UUID

data class PendingToolApproval(
    val id: String,
    val actionId: String,
    val description: String,
    val arguments: Map<String, String>
)

/** Short-lived, single-use approval tickets bound to one exact immutable request. */
class ToolApprovalGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS
) {
    private data class Entry(
        val actionId: String,
        val description: String,
        val arguments: Map<String, String>,
        val expiresAt: Long,
        var approved: Boolean = false
    )
    private val entries = mutableMapOf<String, Entry>()

    init {
        require(ttlMillis in 1L..MAX_TTL_MILLIS)
    }

    @Synchronized
    fun createPending(action: ToolAction, request: ToolRequest): PendingToolApproval {
        require(action.risk == ToolRisk.SENSITIVE || action.risk == ToolRisk.DESTRUCTIVE)
        require(request.isWithinLimits())
        purgeExpired()
        check(entries.size < MAX_PENDING_APPROVALS) {
            "Too many pending or approved tool requests."
        }
        val id = UUID.randomUUID().toString()
        val description = action.description.take(MAX_DESCRIPTION_LENGTH)
        val storedArguments = immutableArguments(request.arguments)
        entries[id] = Entry(action.actionId, description, storedArguments, clock() + ttlMillis)
        return PendingToolApproval(id, action.actionId, description, immutableArguments(storedArguments))
    }

    @Synchronized
    fun pendingApprovals(): List<PendingToolApproval> {
        purgeExpired()
        return entries.filterValues { !it.approved }.map { (id, entry) ->
            snapshot(id, entry)
        }
    }

    /** Atomically records the UI approval and returns the exact immutable request snapshot. */
    @Synchronized
    fun approveAndGet(id: String): PendingToolApproval? {
        purgeExpired()
        val entry = entries[id] ?: return null
        if (entry.approved) return null
        entry.approved = true
        return snapshot(id, entry)
    }

    @Synchronized
    fun approve(id: String): Boolean = approveAndGet(id) != null

    @Synchronized
    fun cancel(id: String): Boolean = entries.remove(id) != null

    @Synchronized
    fun consume(id: String, action: ToolAction, request: ToolRequest): Boolean {
        purgeExpired()
        val entry = entries[id] ?: return false
        if (!entry.approved) return false
        entries.remove(id)
        return entry.actionId == action.actionId &&
            entry.arguments == request.arguments &&
            entry.description == action.description.take(MAX_DESCRIPTION_LENGTH)
    }

    @Synchronized
    private fun purgeExpired() {
        val now = clock()
        entries.entries.removeAll { it.value.expiresAt <= now }
    }

    private fun snapshot(id: String, entry: Entry) = PendingToolApproval(
        id, entry.actionId, entry.description, immutableArguments(entry.arguments)
    )

    private fun immutableArguments(arguments: Map<String, String>): Map<String, String> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(arguments))

    private companion object {
        const val DEFAULT_TTL_MILLIS = 120_000L
        const val MAX_TTL_MILLIS = 300_000L
        const val MAX_DESCRIPTION_LENGTH = 200
        const val MAX_PENDING_APPROVALS = 8
    }
}

package ai.noid.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

class ToolAuditRepository(private val dao: ToolAuditDao) {
    val recentEvents: Flow<List<ToolAuditEvent>> = dao.observeRecent()

    /**
     * Returns false when persistence fails so callers can distinguish an
     * unaudited operation without masking the result of the operation itself.
     * Sensitive values are redacted before the event reaches persistent storage.
     * Coroutine cancellation is propagated so callers can stop promptly.
     */
    suspend fun record(
        action: ToolAction,
        authorization: ToolAuthorization,
        details: String? = null
    ): Boolean = try {
        dao.insert(
            ToolAuditEvent(
                action.actionId,
                action.capability.name,
                authorization.decision.name,
                ToolAuditDetailsSanitizer.sanitize(details, MAX_DETAILS_LENGTH)
            )
        )
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val MAX_DETAILS_LENGTH = 1000
    }
}

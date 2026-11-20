package ai.noid.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Shared execution boundary for tool adapters.
 *
 * It authorizes each action and persists a start event before invoking the
 * operation. A failed pre-audit prevents execution. This coordinator does not
 * discover Android grants; callers must pass capabilities actually obtained
 * from the relevant platform or user-approved scope.
 */
class ToolExecutionCoordinator(
    private val policy: ToolExecutionPolicy = ToolExecutionPolicy()
) {
    suspend fun <T> execute(
        action: ToolAction,
        grantedCapabilities: Set<ToolCapability>,
        userConfirmed: Boolean,
        auditRepository: ToolAuditRepository,
        operation: suspend () -> T
    ): ToolExecutionResult<T> {
        val authorization = policy.evaluate(action, grantedCapabilities, userConfirmed)
        if (authorization.decision != ToolDecision.ALLOW) {
            val audited = auditRepository.record(
                action,
                authorization,
                "blocked: ${authorization.decision}"
            )
            return ToolExecutionResult.Rejected(
                decision = authorization.decision,
                reason = authorization.reason,
                auditRecorded = audited
            )
        }

        if (!auditRepository.record(action, authorization, "started")) {
            return ToolExecutionResult.AuditUnavailable("start")
        }

        return try {
            val value = operation()
            val completionAudited = auditRepository.record(action, authorization, "completed")
            ToolExecutionResult.Completed(value, completionAudited)
        } catch (cancelled: CancellationException) {
            // Preserve a durable terminal audit event even though the caller is
            // cancelled. Never let audit failure replace the original cancellation.
            withContext(NonCancellable) {
                runCatching { auditRepository.record(action, authorization, "cancelled") }
            }
            throw cancelled
        } catch (failure: Exception) {
            val failureAudited = auditRepository.record(
                action,
                authorization,
                "failed: ${failure.javaClass.simpleName}"
            )
            ToolExecutionResult.Failed(
                exceptionType = failure.javaClass.simpleName,
                failureAudited = failureAudited
            )
        }
    }
}

sealed class ToolExecutionResult<out T> {
    data class Rejected(
        val decision: ToolDecision,
        val reason: String,
        val auditRecorded: Boolean
    ) : ToolExecutionResult<Nothing>()

    data class AuditUnavailable(val phase: String) : ToolExecutionResult<Nothing>()

    data class Completed<T>(
        val value: T,
        val completionAudited: Boolean
    ) : ToolExecutionResult<T>()

    data class Failed(
        val exceptionType: String,
        val failureAudited: Boolean
    ) : ToolExecutionResult<Nothing>()
}

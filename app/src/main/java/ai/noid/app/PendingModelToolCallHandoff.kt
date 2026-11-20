package ai.noid.app

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the single-winner handoff between a model tool call and the UI approval flow.
 * Claiming removes the response before execution so a late result cannot be posted
 * as an unrelated assistant message after the model turn has ended.
 */
class PendingModelToolCallHandoff {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun register(id: String, response: CompletableDeferred<String>) {
        require(id.isNotBlank()) { "Pending approval id must not be blank." }
        check(pending.putIfAbsent(id, response) == null) {
            "A model tool response is already registered for this approval."
        }
    }

    fun claim(id: String): CompletableDeferred<String>? = pending.remove(id)

    fun reject(id: String, responseJson: String): Boolean {
        val response = pending.remove(id) ?: return false
        return response.complete(responseJson)
    }

    /** Cancels every outstanding approval and unblocks each waiting model call. */
    fun cancelAll(cancelApproval: (String) -> Unit): Int {
        var cancelled = 0
        pending.entries.toList().forEach { (id, response) ->
            if (pending.remove(id, response)) {
                try {
                    cancelApproval(id)
                } finally {
                    response.complete(ToolCallResponseJson.failure("أُلغيت جولة المحادثة."))
                    cancelled++
                }
            }
        }
        return cancelled
    }

    val size: Int get() = pending.size
}

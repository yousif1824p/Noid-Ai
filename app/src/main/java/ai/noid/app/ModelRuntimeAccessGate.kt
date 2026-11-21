package ai.noid.app

import kotlinx.coroutines.sync.Mutex

/**
 * Prevents model replacement or shutdown while an inference conversation is active.
 *
 * The lock remains held across suspensions (including user approval waits), so a
 * replacement cannot close the native Engine underneath an open Conversation.
 */
internal class ModelRuntimeAccessGate {
    private val mutex = Mutex()

    suspend fun <T> withAccess(block: suspend () -> T): T {
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}

package ai.noid.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException

/** Narrow seam around the blocking HTTPS call, allowing lifecycle policy to be tested. */
interface RemoteChatCall {
    fun execute(): List<RemoteSseDocumentValidator.ValidatedEvent>
    fun cancel()
}

fun interface RemoteChatCallFactory {
    fun create(
        enabled: Boolean,
        userInitiated: Boolean,
        endpoint: String?,
        request: RemoteChatRequest
    ): RemoteChatCall
}

/**
 * Executes one explicitly user-started remote turn.
 *
 * Only bounded user/assistant text from the conversation is serialized.
 * Memories, images, tools, permissions, audit data, and device state are never
 * passed to the remote connector. The caller must invoke cancel() when its
 * owning conversation operation is cancelled.
 */
class RemoteChatTurnAdapter(
    private val callFactory: RemoteChatCallFactory? = null,
    private val bearerTokenProvider: () -> String? = { null }
) {
    private val lock = Any()
    private var executing = false
    private var cancelRequested = false
    @Volatile private var activeCall: RemoteChatCall? = null

    suspend fun execute(
        conversation: List<ChatMessage>,
        endpoint: String?,
        enabled: Boolean,
        userInitiated: Boolean
    ): String = withContext(Dispatchers.IO) {
        require(enabled) { "Remote connector is disabled." }
        require(userInitiated) { "Remote requests must be started by an explicit user action." }
        synchronized(lock) {
            check(!executing) { "A remote chat request is already active." }
            executing = true
            cancelRequested = false
        }
        var call: RemoteChatCall? = null
        try {
            val messages = conversation.takeLast(RemoteChatRequestPolicy.MAX_MESSAGES).map { message ->
                RemoteChatMessage(
                    role = if (message.fromUser) "user" else "assistant",
                    content = message.text
                )
            }
            val request = RemoteChatRequest(UUID.randomUUID().toString(), messages)
            RemoteChatRequestPolicy.validate(request)

            call = callFactory?.create(enabled, userInitiated, endpoint, request)
                ?: RemoteChatHttpCall(
                    enabled = enabled,
                    userInitiated = userInitiated,
                    endpoint = endpoint,
                    request = request,
                    bearerToken = bearerTokenProvider()
                )
            val cancelledBeforeActivation = synchronized(lock) {
                if (cancelRequested) {
                    true
                } else {
                    activeCall = call
                    false
                }
            }
            if (cancelledBeforeActivation) {
                call.cancel()
                throw CancellationException("Remote request was cancelled before connection startup.")
            }

            val events = call.execute()
            ensureNotCancelled()
            val output = StringBuilder()
            events.forEach { item ->
                ensureNotCancelled()
                when (item.frame.event) {
                    "delta" -> {
                        val text = requireNotNull(item.envelope.text)
                        require(text.length <= RemoteSseStreamValidator.MAX_OUTPUT_CHARS - output.length) {
                            "Remote response exceeded the output limit."
                        }
                        output.append(text)
                    }
                    "error" -> throw IOException(
                        "Remote assistant request failed (${item.envelope.errorCode})."
                    )
                }
            }
            ensureNotCancelled()
            if (output.isEmpty()) throw IOException("Remote assistant returned an empty response.")
            output.toString()
        } finally {
            synchronized(lock) {
                if (activeCall === call) activeCall = null
                executing = false
            }
        }
    }

    private fun ensureNotCancelled() {
        if (synchronized(lock) { cancelRequested }) {
            throw CancellationException("Remote request was cancelled.")
        }
    }

    /** Interrupts the active HTTPS call, including cancellation during call construction. */
    fun cancel() {
        val call = synchronized(lock) {
            cancelRequested = true
            activeCall
        }
        call?.cancel()
    }
}

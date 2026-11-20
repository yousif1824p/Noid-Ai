package ai.noid.app

import java.nio.charset.StandardCharsets

/**
 * Privacy and size gate for the future opt-in remote chat adapter.
 *
 * The request model deliberately contains only a request ID and text messages.
 * It has no fields for memories, files, images, permissions, audit records,
 * device identifiers, or tool invocations.
 */
data class RemoteChatMessage(
    val role: String,
    val content: String
)

data class RemoteChatRequest(
    val requestId: String,
    val messages: List<RemoteChatMessage>
)

object RemoteChatRequestPolicy {
    const val MAX_REQUEST_ID_CHARS = 64
    const val MAX_MESSAGES = 20
    const val MAX_MESSAGE_CHARS = 32_000
    const val MAX_CONTEXT_UTF8_BYTES = 64 * 1024
    const val MAX_BODY_BYTES = 1024 * 1024

    private val requestIdPattern = Regex("[A-Za-z0-9._~-]{1,64}")
    private val allowedRoles = setOf("user", "assistant")

    /** Returns the same request after validating its complete bounded shape. */
    fun validate(request: RemoteChatRequest): RemoteChatRequest {
        require(request.requestId.length <= MAX_REQUEST_ID_CHARS &&
            requestIdPattern.matches(request.requestId)) {
            "Remote request ID is invalid."
        }
        require(request.messages.isNotEmpty() && request.messages.size <= MAX_MESSAGES) {
            "Remote conversation must contain between 1 and 20 messages."
        }
        require(request.messages.any { it.role == "user" }) {
            "Remote conversation must contain at least one user message."
        }

        var contextBytes = 0L
        request.messages.forEach { message ->
            require(message.role in allowedRoles) {
                "Remote message role is not supported."
            }
            require(message.content.isNotBlank() && message.content.length <= MAX_MESSAGE_CHARS) {
                "Remote message content is empty or exceeds the character limit."
            }
            contextBytes += message.content.toByteArray(StandardCharsets.UTF_8).size
            require(contextBytes <= MAX_CONTEXT_UTF8_BYTES) {
                "Remote conversation context exceeds the UTF-8 byte limit."
            }
        }
        return request
    }

    /** Call after serialization and before opening a network connection. */
    fun validateSerializedBodySize(body: ByteArray) {
        require(body.isNotEmpty() && body.size <= MAX_BODY_BYTES) {
            "Remote request body is empty or exceeds the byte limit."
        }
    }
}

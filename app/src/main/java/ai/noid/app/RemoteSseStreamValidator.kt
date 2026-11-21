package ai.noid.app

/**
 * Validates the semantic envelope and lifecycle of already-decoded remote SSE
 * frames. JSON decoding belongs to the transport adapter; remote data never
 * becomes a tool invocation or local authorization grant.
 */
data class RemoteSseEnvelope(
    val requestId: String,
    val protocolVersion: String,
    val sequence: Long,
    val text: String? = null,
    val errorCode: String? = null
)

class RemoteSseStreamValidator(
    private val expectedRequestId: String
) {
    private var started = false
    private var terminal = false
    private var lastSequence = 0L
    private var outputChars = 0

    init {
        require(expectedRequestId.isNotBlank() && expectedRequestId.length <= MAX_REQUEST_ID_CHARS) {
            "Expected request ID is invalid."
        }
    }

    fun accept(frame: RemoteSseFrame, envelope: RemoteSseEnvelope) {
        check(!terminal) { "Remote stream emitted an event after its terminal event." }
        require(envelope.requestId == expectedRequestId) { "Remote request ID does not match." }
        require(envelope.protocolVersion == PROTOCOL_VERSION) { "Remote protocol version is unsupported." }
        require(envelope.sequence > lastSequence) { "Remote event sequence is duplicate or out of order." }

        when (frame.event) {
            "started" -> {
                require(!started && lastSequence == 0L) { "Remote stream has an invalid started event." }
                require(envelope.text == null && envelope.errorCode == null) {
                    "Started event contains unexpected payload fields."
                }
                started = true
            }
            "delta" -> {
                require(started) { "Remote delta arrived before started." }
                val text = requireNotNull(envelope.text) { "Remote delta is missing text." }
                require(text.isNotEmpty()) { "Remote delta text is empty." }
                require(envelope.errorCode == null) { "Remote delta contains an error code." }
                require(text.length <= MAX_OUTPUT_CHARS - outputChars) {
                    "Remote output exceeded the maximum length."
                }
                outputChars += text.length
            }
            "completed" -> {
                require(started) { "Remote completion arrived before started." }
                require(envelope.text == null && envelope.errorCode == null) {
                    "Completed event contains unexpected payload fields."
                }
                terminal = true
            }
            "error" -> {
                require(started) { "Remote error arrived before started." }
                require(envelope.text == null) { "Remote error contains unexpected text." }
                val code = requireNotNull(envelope.errorCode) { "Remote error is missing a code." }
                require(code in ALLOWED_ERROR_CODES) { "Remote error code is not supported." }
                terminal = true
            }
            else -> throw IllegalArgumentException("Remote event name is not supported.")
        }
        lastSequence = envelope.sequence
    }

    fun finish() {
        check(started) { "Remote stream ended before started." }
        check(terminal) { "Remote stream ended without a terminal event." }
    }

    companion object {
        const val PROTOCOL_VERSION = "1"
        const val MAX_REQUEST_ID_CHARS = 64
        const val MAX_OUTPUT_CHARS = 32_000
        val ALLOWED_ERROR_CODES = setOf(
            "unauthorized",
            "forbidden",
            "invalid_request",
            "request_too_large",
            "rate_limited",
            "unavailable",
            "timeout",
            "cancelled",
            "output_limit",
            "internal_error"
        )
    }
}

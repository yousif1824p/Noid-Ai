package ai.noid.app

import org.json.JSONException
import org.json.JSONObject

/** Strict parser for one already-framed remote SSE event. */
object RemoteSseEnvelopeParser {
    const val MAX_TEXT_CHARS = 16_384
    const val MAX_ERROR_CODE_CHARS = 64
    private val requiredKeys = setOf("request_id", "protocol_version", "sequence")
    private val optionalKeys = setOf("text", "error_code")

    fun parse(frame: RemoteSseFrame): RemoteSseEnvelope {
        require(frame.data.isNotBlank() && frame.data.length <= RemoteSseFrameDecoder.MAX_EVENT_CHARS) {
            "Remote event JSON is empty or exceeds the event limit."
        }
        val json = try {
            JSONObject(frame.data)
        } catch (error: Exception) {
            throw IllegalArgumentException("Remote event JSON is malformed.", error)
        }
        val keys = mutableSetOf<String>()
        val iterator = json.keys()
        while (iterator.hasNext()) keys += iterator.next()
        require(keys.containsAll(requiredKeys)) { "Remote event JSON is missing required fields." }
        require(keys.all { it in requiredKeys || it in optionalKeys }) {
            "Remote event JSON contains unsupported fields."
        }

        val requestId = json.opt("request_id") as? String
            ?: throw IllegalArgumentException("Remote request ID must be a string.")
        require(requestId.isNotBlank() && requestId.length <= RemoteSseStreamValidator.MAX_REQUEST_ID_CHARS) {
            "Remote request ID is invalid."
        }
        val version = json.opt("protocol_version") as? String
            ?: throw IllegalArgumentException("Remote protocol version must be a string.")
        require(version == RemoteSseStreamValidator.PROTOCOL_VERSION) {
            "Remote protocol version is unsupported."
        }

        // Validate the original JSON token, not JSONObject's numeric conversion:
        // exponent and decimal forms must not be normalized into an integer.
        val sequenceToken = Regex(""""sequence"\s*:\s*([^,\s}]+)""")
            .find(frame.data)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Remote sequence is missing.")
        require(sequenceToken.matches(Regex("[1-9][0-9]*"))) {
            "Remote sequence must be a positive integer."
        }
        val sequence = sequenceToken.toLongOrNull()
            ?: throw IllegalArgumentException("Remote sequence is out of range.")

        val text = if (json.has("text")) {
            require(!json.isNull("text")) { "Remote text cannot be null." }
            json.opt("text") as? String
                ?: throw IllegalArgumentException("Remote text must be a string.")
        } else null
        require(text == null || text.length <= MAX_TEXT_CHARS) {
            "Remote text exceeds the event limit."
        }

        val errorCode = if (json.has("error_code")) {
            require(!json.isNull("error_code")) { "Remote error code cannot be null." }
            json.opt("error_code") as? String
                ?: throw IllegalArgumentException("Remote error code must be a string.")
        } else null
        require(errorCode == null || (errorCode.isNotBlank() && errorCode.length <= MAX_ERROR_CODE_CHARS)) {
            "Remote error code is invalid."
        }

        return RemoteSseEnvelope(requestId, version, sequence, text, errorCode)
    }
}

/** Pure composition of framing, JSON parsing, and request-bound lifecycle validation. */
object RemoteSseDocumentValidator {
    data class ValidatedEvent(val frame: RemoteSseFrame, val envelope: RemoteSseEnvelope)

    fun validate(document: String, expectedRequestId: String): List<ValidatedEvent> {
        val validator = RemoteSseStreamValidator(expectedRequestId)
        val validated = RemoteSseFrameDecoder.decode(document).map { frame ->
            val envelope = RemoteSseEnvelopeParser.parse(frame)
            validator.accept(frame, envelope)
            ValidatedEvent(frame, envelope)
        }
        validator.finish()
        return validated
    }
}

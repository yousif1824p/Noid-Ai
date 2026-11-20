package ai.noid.app

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Serializes only the explicitly bounded remote-chat request schema.
 *
 * This class performs no network I/O and never reads local memories, files,
 * permissions, audit events, device identifiers, or tool state.
 */
object RemoteChatJsonSerializer {
    fun serialize(request: RemoteChatRequest): ByteArray {
        val validated = RemoteChatRequestPolicy.validate(request)
        val messages = JSONArray()
        validated.messages.forEach { message ->
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.content)
            )
        }
        val body = JSONObject()
            .put("request_id", validated.requestId)
            .put("messages", messages)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        RemoteChatRequestPolicy.validateSerializedBodySize(body)
        return body
    }
}

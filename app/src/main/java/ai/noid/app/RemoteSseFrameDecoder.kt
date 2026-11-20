package ai.noid.app

/**
 * Strict bounded decoder for complete Server-Sent Events documents.
 *
 * Supports the SSE line endings (LF, CRLF, and CR). This is only the framing
 * layer: callers must still validate event JSON, request IDs, versions, and
 * sequence numbers. Remote content is never interpreted as a command.
 */
data class RemoteSseFrame(val event: String, val data: String)

object RemoteSseFrameDecoder {
    const val MAX_DOCUMENT_CHARS = 256 * 1024
    const val MAX_EVENT_CHARS = 16 * 1024
    const val MAX_EVENTS = 64
    private val allowedEvents = setOf("started", "delta", "completed", "error")

    fun decode(document: String): List<RemoteSseFrame> {
        require(document.length <= MAX_DOCUMENT_CHARS) { "SSE document exceeds the size limit." }
        if (document.isEmpty()) return emptyList()
        val frames = mutableListOf<RemoteSseFrame>()
        var eventName: String? = null
        val dataLines = mutableListOf<String>()
        var eventChars = 0

        fun dispatch() {
            if (eventName == null && dataLines.isEmpty()) {
                eventChars = 0
                return
            }
            val name = requireNotNull(eventName) { "SSE frame is missing an event name." }
            require(name in allowedEvents) { "SSE event name is not supported." }
            require(dataLines.isNotEmpty()) { "SSE frame is missing data." }
            require(frames.size < MAX_EVENTS) { "SSE document contains too many events." }
            frames += RemoteSseFrame(name, dataLines.joinToString("\n"))
            eventName = null
            dataLines.clear()
            eventChars = 0
        }

        fun consumeLine(line: String) {
            if (line.isEmpty()) {
                dispatch()
                return
            }
            if (line.startsWith(':')) return
            eventChars += line.length
            require(eventChars <= MAX_EVENT_CHARS) { "SSE event exceeds the size limit." }
            val separator = line.indexOf(':')
            val field = if (separator < 0) line else line.substring(0, separator)
            var value = if (separator < 0) "" else line.substring(separator + 1)
            if (value.startsWith(' ')) value = value.drop(1)
            when (field) {
                "event" -> {
                    require(eventName == null) { "SSE frame contains duplicate event fields." }
                    eventName = value
                }
                "data" -> dataLines += value
                else -> throw IllegalArgumentException("SSE field is not supported.")
            }
        }

        var lineStart = 0
        var index = 0
        var endedWithLineBreak = false
        while (index < document.length) {
            val char = document[index]
            if (char != '\n' && char != '\r') {
                index++
                endedWithLineBreak = false
                continue
            }
            consumeLine(document.substring(lineStart, index))
            if (char == '\r' && index + 1 < document.length && document[index + 1] == '\n') {
                index += 2
            } else {
                index++
            }
            lineStart = index
            endedWithLineBreak = true
        }

        require(endedWithLineBreak && lineStart == document.length) {
            "SSE document ended with an incomplete line or frame."
        }
        require(eventName == null && dataLines.isEmpty()) {
            "SSE document ended before the final frame delimiter."
        }
        return frames.toList()
    }
}

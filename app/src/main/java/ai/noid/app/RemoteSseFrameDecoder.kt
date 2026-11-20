package ai.noid.app

/**
 * Strict bounded decoder for complete Server-Sent Events documents.
 *
 * This is only the framing layer: callers must still decode and validate the
 * event JSON schema, request ID, protocol version, and sequence numbers.
 * It performs no networking and never interprets remote content as commands.
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
        var lineStart = 0
        var sawFinalLineBreak = false

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

        for (index in document.indices) {
            if (document[index] != '\n') continue
            var line = document.substring(lineStart, index)
            if (line.endsWith('\r')) line = line.dropLast(1)
            sawFinalLineBreak = index == document.lastIndex
            lineStart = index + 1
            if (line.isEmpty()) {
                dispatch()
                continue
            }
            if (line.startsWith(':')) continue
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
        require(sawFinalLineBreak && lineStart == document.length) {
            "SSE document ended with an incomplete line or frame."
        }
        require(eventName == null && dataLines.isEmpty()) {
            "SSE document ended before the final frame delimiter."
        }
        return frames.toList()
    }
}

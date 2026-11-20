package ai.noid.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection

/**
 * User-started, opt-in HTTPS transport for the bounded remote-chat protocol.
 *
 * The caller must run execute() off the main thread. The default TLS
 * implementation and certificate validation are provided by Android.
 * Redirects are disabled and remote output is parsed as data only.
 */
class RemoteChatHttpCall(
    enabled: Boolean,
    userInitiated: Boolean,
    endpoint: String?,
    private val request: RemoteChatRequest,
    /** Optional token loaded from Android Keystore after explicit user configuration. */
    bearerToken: String? = null,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val connectionFactory: (URL) -> HttpsURLConnection = { url ->
        url.openConnection() as? HttpsURLConnection
            ?: throw IOException("Remote endpoint did not create an HTTPS connection.")
    }
) : RemoteChatCall {
    private val uri = RemoteConnectionPolicy.validate(
        enabled = enabled,
        endpoint = endpoint,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis
    ) ?: throw IllegalArgumentException("Remote connector is disabled.")
    private val body = RemoteChatJsonSerializer.serialize(request)
    private val authorizationHeader = bearerToken?.also(RemoteBearerTokenPolicy::validate)
        ?.let { "Bearer $it" }
    @Volatile private var connection: HttpsURLConnection? = null
    @Volatile private var cancelled = false

    init {
        require(userInitiated) { "Remote requests must be started by an explicit user action." }
    }

    /** Performs one bounded request and validates the complete SSE lifecycle. */
    @Throws(IOException::class)
    override fun execute(): List<RemoteSseDocumentValidator.ValidatedEvent> {
        checkNotCancelled()
        val opened = connectionFactory(URL(uri.toASCIIString()))
        connection = opened
        try {
            checkNotCancelled()
            opened.instanceFollowRedirects = false
            opened.useCaches = false
            opened.connectTimeout = connectTimeoutMillis
            opened.readTimeout = readTimeoutMillis
            opened.requestMethod = "POST"
            opened.doOutput = true
            opened.setRequestProperty("Accept", "text/event-stream")
            opened.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            opened.setRequestProperty("Cache-Control", "no-store")
            opened.setRequestProperty("Connection", "close")
            authorizationHeader?.let { opened.setRequestProperty("Authorization", it) }
            opened.setFixedLengthStreamingMode(body.size)

            opened.outputStream.use { output ->
                checkNotCancelled()
                output.write(body)
                output.flush()
            }
            checkNotCancelled()

            val status = opened.responseCode
            if (status != HttpsURLConnection.HTTP_OK) {
                throw IOException("Remote server returned HTTP status $status.")
            }
            val mediaType = opened.contentType?.substringBefore(';')?.trim()
            if (!mediaType.equals("text/event-stream", ignoreCase = true)) {
                throw IOException("Remote server returned an unexpected content type.")
            }

            val response = readBoundedResponse(opened)
            checkNotCancelled()
            return try {
                RemoteSseDocumentValidator.validate(
                    decodeUtf8Strict(response),
                    request.requestId
                )
            } catch (error: IllegalArgumentException) {
                throw IOException("Remote server returned an invalid event stream.", error)
            } catch (error: IllegalStateException) {
                throw IOException("Remote server returned an incomplete event stream.", error)
            }
        } finally {
            connection = null
            opened.disconnect()
        }
    }

    /** May be called from another thread to interrupt an in-flight request. */
    override fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    private fun readBoundedResponse(opened: HttpsURLConnection): ByteArray {
        val output = ByteArrayOutputStream()
        opened.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                checkNotCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count > MAX_RESPONSE_BYTES - output.size()) {
                    throw IOException("Remote event stream exceeds the response size limit.")
                }
                output.write(buffer, 0, count)
            }
        }
        if (output.size() == 0) throw IOException("Remote server returned an empty event stream.")
        return output.toByteArray()
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IOException("Remote event stream is not valid UTF-8.", error)
    }

    private fun checkNotCancelled() {
        if (cancelled) throw CancellationException("Remote request was cancelled.")
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}

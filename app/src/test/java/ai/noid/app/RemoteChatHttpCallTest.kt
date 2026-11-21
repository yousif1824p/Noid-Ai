package ai.noid.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.Principal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteChatHttpCallTest {
    private val request = RemoteChatRequest(
        requestId = "manual-test-1",
        messages = listOf(RemoteChatMessage("user", "hello"))
    )

    private val validSse = """
        event: started
        data: {"request_id":"manual-test-1","protocol_version":"1","sequence":1}

        event: delta
        data: {"request_id":"manual-test-1","protocol_version":"1","sequence":2,"text":"hello"}

        event: completed
        data: {"request_id":"manual-test-1","protocol_version":"1","sequence":3}

    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    @Test
    fun rejectsRequestsThatWereNotStartedByTheUser() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatHttpCall(
                enabled = true,
                userInitiated = false,
                endpoint = "https://assistant.example/v1/chat",
                request = request
            )
        }
    }

    @Test
    fun rejectsDisabledConnectorBeforeOpeningNetwork() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatHttpCall(
                enabled = false,
                userInitiated = true,
                endpoint = null,
                request = request
            )
        }
    }

    @Test
    fun rejectsNonHttpsEndpointBeforeOpeningNetwork() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatHttpCall(
                enabled = true,
                userInitiated = true,
                endpoint = "http://assistant.example/v1/chat",
                request = request
            )
        }
    }

    @Test
    fun rejectsInvalidRequestBeforeOpeningNetwork() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatHttpCall(
                enabled = true,
                userInitiated = true,
                endpoint = "https://assistant.example/v1/chat",
                request = request.copy(messages = listOf(RemoteChatMessage("tool", "execute")))
            )
        }
    }

    @Test
    fun postsOnlyTheBoundedJsonBodyAndReturnsValidatedEvents() {
        val fake = FakeHttpsConnection(URL("https://assistant.example/v1/chat"), validSse.toByteArray())
        val call = newCall({ _: URL -> fake })

        val events = call.execute()

        assertEquals(listOf("started", "delta", "completed"), events.map { it.frame.event })
        assertEquals("hello", events[1].envelope.text)
        val sent = fake.requestBody.toString(Charsets.UTF_8)
        assertTrue(sent.contains("\"request_id\":\"manual-test-1\""))
        assertTrue(sent.contains("\"content\":\"hello\""))
        assertTrue(!sent.contains("tool"))
        assertEquals("POST", fake.requestMethod)
        assertEquals("application/json; charset=utf-8", fake.getRequestProperty("Content-Type"))
        assertEquals("text/event-stream", fake.getRequestProperty("Accept"))
        assertEquals("no-store", fake.getRequestProperty("Cache-Control"))
        assertEquals(null, fake.getRequestProperty("Authorization"))
        assertTrue(!fake.instanceFollowRedirects)
        assertTrue(fake.disconnected)
    }

    @Test
    fun exchangesARealTlsSseResponseOverTheNetwork() {
        val server = MockWebServer()
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverTls = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody(validSse)
        )
        server.start()
        try {
            val call = RemoteChatHttpCall(
                enabled = true,
                userInitiated = true,
                endpoint = "https://assistant.example/v1/chat",
                request = request,
                connectionFactory = {
                    (server.url("/v1/chat").toUrl().openConnection() as HttpsURLConnection).apply {
                        sslSocketFactory = clientTls.sslSocketFactory()
                    }
                }
            )

            val events = call.execute()
            val received = server.takeRequest()

            assertEquals("POST", received.method)
            assertEquals("/v1/chat", received.path)
            assertEquals("hello", received.body.readUtf8().let { body ->
                org.json.JSONObject(body).getJSONArray("messages")
                    .getJSONObject(0).getString("content")
            })
            assertEquals(listOf("started", "delta", "completed"), events.map { it.frame.event })
            assertEquals("hello", events[1].envelope.text)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sendsValidatedBearerCredentialOnlyInAuthorizationHeader() {
        val fake = FakeHttpsConnection(URL("https://assistant.example/v1/chat"), validSse.toByteArray())

        newCall({ _: URL -> fake }, bearerToken = "secure.token_123").execute()

        assertEquals("Bearer secure.token_123", fake.getRequestProperty("Authorization"))
        assertTrue(!fake.requestBody.toString(Charsets.UTF_8).contains("secure.token_123"))
    }

    @Test
    fun rejectsInvalidBearerCredentialBeforeOpeningConnection() {
        var opened = false
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChatHttpCall(
                enabled = true,
                userInitiated = true,
                endpoint = "https://assistant.example/v1/chat",
                request = request,
                bearerToken = "token\\r\\nInjected: yes",
                connectionFactory = {
                    opened = true
                    FakeHttpsConnection(it, validSse.toByteArray())
                }
            )
        }
        assertTrue(!opened)
    }

    @Test
    fun rejectsUnexpectedHttpStatusAndDisconnects() {
        val fake = FakeHttpsConnection(
            URL("https://assistant.example/v1/chat"),
            validSse.toByteArray(),
            status = 503
        )
        assertThrows(java.io.IOException::class.java) { newCall({ _: URL -> fake }).execute() }
        assertTrue(fake.disconnected)
    }

    @Test
    fun rejectsUnexpectedResponseContentType() {
        val fake = FakeHttpsConnection(
            URL("https://assistant.example/v1/chat"),
            validSse.toByteArray(),
            contentType = "application/json"
        )
        assertThrows(java.io.IOException::class.java) { newCall({ _: URL -> fake }).execute() }
    }

    @Test
    fun rejectsOversizedResponseBeforeParsing() {
        val fake = FakeHttpsConnection(
            URL("https://assistant.example/v1/chat"),
            ByteArray(RemoteChatHttpCall.MAX_RESPONSE_BYTES + 1)
        )
        assertThrows(java.io.IOException::class.java) { newCall({ _: URL -> fake }).execute() }
        assertTrue(fake.disconnected)
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfReplacingInvalidBytes() {
        val fake = FakeHttpsConnection(
            URL("https://assistant.example/v1/chat"),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        )

        assertThrows(java.io.IOException::class.java) { newCall({ _: URL -> fake }).execute() }
        assertTrue(fake.disconnected)
    }

    @Test
    fun rejectsMalformedOrIncompleteSseDocument() {
        val fake = FakeHttpsConnection(
            URL("https://assistant.example/v1/chat"),
            "event: started\ndata: {}\n".toByteArray()
        )
        assertThrows(java.io.IOException::class.java) { newCall({ _: URL -> fake }).execute() }
    }

    @Test
    fun cancellationBeforeExecutionPreventsOpeningConnection() {
        var opened = false
        val call = newCall({ url: URL -> opened = true
            FakeHttpsConnection(url, validSse.toByteArray()) })
        call.cancel()

        assertThrows(java.util.concurrent.CancellationException::class.java) { call.execute() }
        assertTrue(!opened)
    }

    private fun newCall(
        factory: (URL) -> HttpsURLConnection,
        bearerToken: String? = null
    ) = RemoteChatHttpCall(
        enabled = true,
        userInitiated = true,
        endpoint = "https://assistant.example/v1/chat",
        request = request,
        bearerToken = bearerToken,
        connectionFactory = factory
    )

    private class FakeHttpsConnection(
        url: URL,
        private val response: ByteArray,
        private val status: Int = HTTP_OK,
        private val contentType: String = "text/event-stream"
    ) : HttpsURLConnection(url) {
        private val sent = ByteArrayOutputStream()
        var disconnected = false
            private set

        val requestBody: ByteArray get() = sent.toByteArray()

        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getCipherSuite(): String = "TLS_FAKE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal = X500Principal("CN=assistant.example")
        override fun getLocalPrincipal(): Principal? = null
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream = ByteArrayInputStream(response)
        override fun getResponseCode(): Int = status
        override fun getContentType(): String = contentType
    }
}

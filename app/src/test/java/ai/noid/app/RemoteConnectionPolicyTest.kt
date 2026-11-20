package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteConnectionPolicyTest {
    @Test fun disabledConnectorHasNoEndpoint() {
        assertNull(RemoteConnectionPolicy.validate(enabled = false, endpoint = null))
        assertThrows(IllegalArgumentException::class.java) {
            RemoteConnectionPolicy.validate(enabled = false, endpoint = "https://example.com")
        }
    }

    @Test fun acceptsExplicitHttpsEndpoint() {
        val uri = RemoteConnectionPolicy.validate(
            enabled = true,
            endpoint = "https://ai.example.com/v1/chat"
        )
        assertEquals("https", uri?.scheme)
        assertEquals("ai.example.com", uri?.host)
        assertEquals("/v1/chat", uri?.path)
    }

    @Test fun rejectsMissingMalformedAndNonHttpsEndpoints() {
        listOf(null, "", "not a uri", "http://ai.example.com", "//ai.example.com/path").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                RemoteConnectionPolicy.validate(enabled = true, endpoint = it)
            }
        }
    }

    @Test fun rejectsEmbeddedCredentialsQueryAndFragment() {
        listOf(
            "https://user:secret@ai.example.com",
            "https://ai.example.com/path?token=secret",
            "https://ai.example.com/path#fragment"
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                RemoteConnectionPolicy.validate(enabled = true, endpoint = it)
            }
        }
    }

    @Test fun rejectsUnexpectedPortsAndInvalidHosts() {
        listOf("https://ai.example.com:8443", "https:///missing-host", "https://bad host/path").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                RemoteConnectionPolicy.validate(enabled = true, endpoint = it)
            }
        }
    }

    @Test fun rejectsPathTraversalAndEncodedSeparators() {
        listOf(
            "https://ai.example.com/a/../admin",
            "https://ai.example.com/%2e%2e/admin",
            "https://ai.example.com/a%2fb",
            "https://ai.example.com/a%5Cb"
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                RemoteConnectionPolicy.validate(enabled = true, endpoint = it)
            }
        }
    }

    @Test fun boundsEndpointAndTimeouts() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteConnectionPolicy.validate(enabled = true, endpoint = "https://" + "a".repeat(2_050) + ".com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteConnectionPolicy.validate(enabled = true, endpoint = "https://ai.example.com", connectTimeoutMillis = 999)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteConnectionPolicy.validate(enabled = true, endpoint = "https://ai.example.com", readTimeoutMillis = 60_001)
        }
    }

    @Test fun acceptsOnlyBoundedTimeoutsAtEdges() {
        RemoteConnectionPolicy.validate(
            enabled = true,
            endpoint = "https://ai.example.com",
            connectTimeoutMillis = RemoteConnectionPolicy.MIN_TIMEOUT_MILLIS,
            readTimeoutMillis = RemoteConnectionPolicy.MAX_TIMEOUT_MILLIS
        )
    }
}

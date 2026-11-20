package ai.noid.app

import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteBearerTokenPolicyTest {
    @Test
    fun acceptsBoundedBearerTokenCharacters() {
        RemoteBearerTokenPolicy.validate("abcDEF0123._~+/=-")
        RemoteBearerTokenPolicy.validate("a".repeat(RemoteBearerTokenPolicy.MAX_TOKEN_CHARS))
    }

    @Test
    fun rejectsEmptyToken() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteBearerTokenPolicy.validate("")
        }
    }

    @Test
    fun rejectsOversizedToken() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteBearerTokenPolicy.validate("a".repeat(RemoteBearerTokenPolicy.MAX_TOKEN_CHARS + 1))
        }
    }

    @Test
    fun rejectsWhitespaceAndHeaderInjection() {
        listOf("token value", "token\nInjected: yes", "token\rInjected: yes", "token\r\nInjected: yes", "token\tvalue", " token", "token ").forEach { token ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteBearerTokenPolicy.validate(token)
            }
        }
    }

    @Test
    fun rejectsUnsupportedCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteBearerTokenPolicy.validate("token:with:semicolon")
        }
    }
}

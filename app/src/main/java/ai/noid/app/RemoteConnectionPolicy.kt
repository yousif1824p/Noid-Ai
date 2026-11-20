package ai.noid.app

import java.net.URI

/**
 * Fail-closed validation for a user-configured remote HTTPS endpoint.
 * This policy does not open a connection or persist credentials.
 */
object RemoteConnectionPolicy {
    const val MIN_TIMEOUT_MILLIS = 1_000
    const val MAX_TIMEOUT_MILLIS = 60_000
    const val MAX_ENDPOINT_CHARS = 2_048

    fun validate(
        enabled: Boolean,
        endpoint: String?,
        connectTimeoutMillis: Int = 15_000,
        readTimeoutMillis: Int = 30_000
    ): URI? {
        require(connectTimeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            "Remote connect timeout is outside the allowed range."
        }
        require(readTimeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            "Remote read timeout is outside the allowed range."
        }

        if (!enabled) {
            require(endpoint.isNullOrBlank()) {
                "A disabled remote connector cannot retain an active endpoint."
            }
            return null
        }

        val raw = requireNotNull(endpoint) { "Remote endpoint is required when enabled." }
        require(raw.isNotBlank() && raw.length <= MAX_ENDPOINT_CHARS && raw == raw.trim()) {
            "Remote endpoint is empty, overlong, or contains surrounding whitespace."
        }
        val uri = try {
            URI(raw)
        } catch (error: Exception) {
            throw IllegalArgumentException("Remote endpoint is malformed.", error)
        }
        require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true)) {
            "Remote endpoint must use HTTPS."
        }
        require(uri.rawUserInfo == null && uri.userInfo == null) {
            "Credentials must not be embedded in the remote endpoint."
        }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "Remote endpoint cannot contain a query or fragment."
        }
        val host = uri.host
        require(!host.isNullOrBlank() && host.length <= 253 && host.none { it.isWhitespace() }) {
            "Remote endpoint must contain a valid host."
        }
        require(uri.port == -1 || uri.port == 443) {
            "Remote endpoint must use the default HTTPS port."
        }
        val path = uri.rawPath.orEmpty()
        require(path.length <= 512 && !path.contains('\\')) {
            "Remote endpoint path is invalid or too long."
        }
        require(!Regex("(?i)%2f|%5c|%2e").containsMatchIn(path)) {
            "Remote endpoint path contains encoded separators or dot segments."
        }
        require(path.split('/').none { it == "." || it == ".." }) {
            "Remote endpoint path cannot traverse directories."
        }
        return uri
    }
}

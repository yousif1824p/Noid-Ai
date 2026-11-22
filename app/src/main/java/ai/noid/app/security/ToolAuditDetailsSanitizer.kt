package ai.noid.app

/**
 * Removes common credential forms before audit details are persisted.
 * This is defense in depth, not a substitute for avoiding secrets in tool output.
 */
object ToolAuditDetailsSanitizer {
    private val bearer = Regex("(?i)(\\bauthorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val namedSecret = Regex(
        "(?i)(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret)\\b\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;]+)"
    )
    private val querySecret = Regex(
        "(?i)([?&](?:token|api_key|apikey|password|secret|access_token|refresh_token)=)[^&#\\s]+"
    )
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b")
    private val urlCredentials = Regex("(?i)(://)[^/@\\s:]+:[^/@\\s]+@")

    fun sanitize(value: String?, maxLength: Int): String? {
        require(maxLength >= 0) { "الحد الأقصى غير صالح." }
        if (value == null) return null
        val redacted = value
            .replace(bearer, "$1[REDACTED]")
            .replace(namedSecret, "$1[REDACTED]")
            .replace(querySecret, "$1[REDACTED]")
            .replace(jwt, "[REDACTED_JWT]")
            .replace(urlCredentials, "$1[REDACTED]@")
        return redacted.take(maxLength)
    }
}

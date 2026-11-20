package ai.noid.app

/** Best-effort display-only redaction for common quoted secret assignments. */
internal object ProjectPreviewSecretRedactor {
    private val pattern = Regex(
        """(?i)(\b(?:api[_-]?key|client[_-]?secret|password|passwd|access[_-]?token)\b\s*[:=]\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')"""
    )

    fun redact(line: String): String = pattern.replace(line) { match ->
        val quotedValue = match.groupValues[2]
        match.groupValues[1] + quotedValue.first() + "[REDACTED]" + quotedValue.last()
    }
}

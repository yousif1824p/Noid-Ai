package ai.noid.app

/** Best-effort display-only redaction for common secret assignments and headers. */
internal object ProjectPreviewSecretRedactor {
    private val pattern = Regex(
        """(?i)(\b(?:api[_-]?key|client[_-]?secret|password|passwd|access[_-]?token|refresh[_-]?token|private[_-]?key|secret[_-]?key|authorization)\b\"?\s*[:=]\s*)("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|(?:(?:Bearer|Basic)\s+)?[^\s,;#}\])]+)"""
    )

    fun redact(line: String): String = pattern.replace(line) { match ->
        val prefix = match.groupValues[1]
        val value = match.groupValues[2]
        if (value.startsWith('"') || value.startsWith('\'')) {
            prefix + value.first() + "[REDACTED]" + value.last()
        } else {
            prefix + "[REDACTED]"
        }
    }
}

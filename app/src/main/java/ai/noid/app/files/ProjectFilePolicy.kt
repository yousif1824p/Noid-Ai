package ai.noid.app

import java.util.Locale

/**
 * Conservative policy for source/configuration files in a user-selected project tree.
 * Binary files, path traversal, and Unicode format controls are rejected; SAF permissions remain the authority.
 */
object ProjectFilePolicy {
    private val editableExtensions = setOf(
        "kt", "kts", "java", "xml", "json", "yaml", "yml", "toml",
        "properties", "gradle", "md", "txt", "py", "js", "ts", "html",
        "css", "sh", "pro", "sql", "gitignore"
    )

    const val MAX_TEXT_FILE_BYTES: Long = 2L * 1024L * 1024L
    const val MAX_RELATIVE_PATH_CHARS: Int = 512
    const val MAX_PATH_SEGMENTS: Int = 64
    const val MAX_PATH_SEGMENT_CHARS: Int = 255

    fun containsUnsafePathCharacters(value: String): Boolean =
        value.any {
            it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
        }

    fun normalizeRelativePath(path: String): String? {
        if (path.length > MAX_RELATIVE_PATH_CHARS || containsUnsafePathCharacters(path)) return null
        val candidate = path.trim().replace('\\', '/')
        if (candidate.isEmpty() || candidate.startsWith("/") || candidate.contains('\u0000')) return null
        val segments = candidate.split('/')
        if (segments.size > MAX_PATH_SEGMENTS || segments.any {
                it.isBlank() || it == "." || it == ".." || it.length > MAX_PATH_SEGMENT_CHARS
            }) return null
        return segments.joinToString("/")
    }

    fun isEditableTextFile(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in editableExtensions || name == ".gitignore"
    }
}

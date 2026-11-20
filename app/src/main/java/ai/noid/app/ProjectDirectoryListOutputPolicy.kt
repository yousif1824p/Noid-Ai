package ai.noid.app

/** Formats untrusted SAF directory entries without cutting an accepted entry at the output boundary. */
object ProjectDirectoryListOutputPolicy {
    fun format(
        entries: List<ProjectFileEntry>,
        maxEntries: Int = ProjectDirectoryListToolAdapter.MAX_ENTRIES,
        maxNameChars: Int = ProjectDirectoryListToolAdapter.MAX_NAME_CHARS,
        maxOutputChars: Int = ProjectDirectoryListToolAdapter.MAX_OUTPUT_CHARS
    ): String {
        require(maxEntries > 0 && maxNameChars > 0 && maxOutputChars > 0)
        if (entries.isEmpty()) return "المجلد فارغ.".take(maxOutputChars)

        val candidateLines = entries.take(maxEntries).map { entry ->
            val safeName = entry.name.map {
                if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it
            }.joinToString("").take(maxNameChars)
            val kind = if (entry.isDirectory) "DIR" else "FILE"
            "$kind\t$safeName\t" + entry.sizeBytes.coerceAtLeast(0) + " bytes"
        }

        val accepted = mutableListOf<String>()
        for (line in candidateLines) {
            val nextCount = accepted.size + 1
            val omitted = entries.size - nextCount
            val suffix = if (omitted > 0) "\n… تم إخفاء $omitted عنصراً إضافياً." else ""
            val candidate = (accepted + line).joinToString("\n") + suffix
            if (candidate.length > maxOutputChars) break
            accepted += line
        }

        val omitted = entries.size - accepted.size
        val body = accepted.joinToString("\n")
        val suffix = if (omitted > 0) "… تم إخفاء $omitted عنصراً إضافياً." else ""
        val result = when {
            body.isEmpty() -> suffix
            suffix.isEmpty() -> body
            else -> "$body\n$suffix"
        }
        return result.take(maxOutputChars)
    }
}

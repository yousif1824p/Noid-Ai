package ai.noid.app

enum class ProjectDiffKind { UNCHANGED, ADDED, REMOVED }

data class ProjectDiffLine(val kind: ProjectDiffKind, val text: String)

data class ProjectDiffResult(
    val lines: List<ProjectDiffLine>,
    val additions: Int,
    val removals: Int,
    val truncated: Boolean
)

/** Bounded line diff; avoids unbounded quadratic work on very large source files. */
object ProjectTextDiff {
    const val MAX_LINES_PER_SIDE = 250

    fun compare(original: String, updated: String): ProjectDiffResult {
        val before = original.split('\n')
        val after = updated.split('\n')
        if (before.size > MAX_LINES_PER_SIDE || after.size > MAX_LINES_PER_SIDE) {
            // A complete replacement view is less compact but remains truthful and
            // bounded by the proposal repository's 2 MiB per-snapshot limit.
            val completeReplacement = buildList(before.size + after.size) {
                before.forEach { add(ProjectDiffLine(ProjectDiffKind.REMOVED, it)) }
                after.forEach { add(ProjectDiffLine(ProjectDiffKind.ADDED, it)) }
            }
            return ProjectDiffResult(
                lines = completeReplacement,
                additions = after.size,
                removals = before.size,
                truncated = false
            )
        }

        val lcs = Array(before.size + 1) { IntArray(after.size + 1) }
        for (i in before.indices.reversed()) {
            for (j in after.indices.reversed()) {
                lcs[i][j] = if (before[i] == after[j]) {
                    1 + lcs[i + 1][j + 1]
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        val lines = mutableListOf<ProjectDiffLine>()
        var i = 0
        var j = 0
        var additions = 0
        var removals = 0
        while (i < before.size && j < after.size) {
            when {
                before[i] == after[j] -> {
                    lines += ProjectDiffLine(ProjectDiffKind.UNCHANGED, before[i])
                    i++
                    j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    lines += ProjectDiffLine(ProjectDiffKind.REMOVED, before[i])
                    removals++
                    i++
                }
                else -> {
                    lines += ProjectDiffLine(ProjectDiffKind.ADDED, after[j])
                    additions++
                    j++
                }
            }
        }
        while (i < before.size) {
            lines += ProjectDiffLine(ProjectDiffKind.REMOVED, before[i++])
            removals++
        }
        while (j < after.size) {
            lines += ProjectDiffLine(ProjectDiffKind.ADDED, after[j++])
            additions++
        }
        return ProjectDiffResult(lines, additions, removals, truncated = false)
    }
}

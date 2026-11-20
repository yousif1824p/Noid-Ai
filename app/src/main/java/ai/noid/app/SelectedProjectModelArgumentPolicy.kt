package ai.noid.app

/**
 * Binds model-supplied project paths to the app-owned active SAF selection.
 * The model may choose only a validated relative path; it cannot select a URI
 * or selection token. An empty path explicitly denotes the selected root.
 */
object SelectedProjectModelArgumentPolicy {
    fun bindPath(
        arguments: Map<String, Any?>,
        activeSelectionId: String?,
        pathArgument: String,
        selectionArgument: String
    ): Map<String, String>? {
        if (pathArgument.isBlank() || selectionArgument.isBlank() || pathArgument == selectionArgument) {
            return null
        }
        if (arguments.keys != setOf(pathArgument)) return null
        val suppliedPath = arguments[pathArgument] as? String ?: return null
        if (activeSelectionId.isNullOrBlank()) return null
        val normalizedPath = if (suppliedPath.isEmpty()) {
            ""
        } else {
            ProjectFilePolicy.normalizeRelativePath(suppliedPath) ?: return null
        }
        return mapOf(selectionArgument to activeSelectionId, pathArgument to normalizedPath)
    }
}

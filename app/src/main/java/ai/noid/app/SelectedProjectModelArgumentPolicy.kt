package ai.noid.app

/**
 * Binds model-supplied project paths to the app-owned active SAF selection.
 * The model may choose only a path; it cannot select a URI or selection token.
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
        val path = arguments[pathArgument] as? String ?: return null
        if (activeSelectionId.isNullOrBlank()) return null
        return mapOf(selectionArgument to activeSelectionId, pathArgument to path)
    }
}

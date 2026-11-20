package ai.noid.app

/**
 * Enforces the narrow input and declared-scope boundary for a local,
 * read-only project scan. The confirmation records user intent; it does
 * not independently prove legal ownership or grant access to files.
 */
object ProjectScanAuthorizationPolicy {
    fun allowsReadOnlyScan(
        selectedTreeUri: String?,
        hasPersistedReadGrant: Boolean,
        userConfirmedAuthorizedScope: Boolean
    ): Boolean {
        if (!hasPersistedReadGrant || !userConfirmedAuthorizedScope) return false
        val value = selectedTreeUri ?: return false
        if (!value.startsWith("content://")) return false
        if (value.any { it.isWhitespace() || it.isISOControl() }) return false

        val authority = value.removePrefix("content://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return authority.isNotBlank()
    }
}

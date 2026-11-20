package ai.noid.app

/**
 * A model import owns only the persisted SAF read grant it added.
 * A grant that existed before the import belongs to the user's prior selection
 * and must remain untouched when the import attempt finishes.
 */
internal object PersistedUriReadGrantPolicy {
    fun shouldReleaseAfterAttempt(hadPersistedReadGrantBeforeAttempt: Boolean): Boolean =
        !hadPersistedReadGrantBeforeAttempt

    /** The image screen releases only grants it acquired itself. */
    fun shouldReleaseAfterScreen(
        hadPersistedReadGrantBeforeSelection: Boolean,
        alreadyOwnedByScreen: Boolean
    ): Boolean = alreadyOwnedByScreen || !hadPersistedReadGrantBeforeSelection
}

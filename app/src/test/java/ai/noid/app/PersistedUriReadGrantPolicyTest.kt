package ai.noid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedUriReadGrantPolicyTest {
    @Test
    fun preservesReadGrantThatPredatedTheImportAttempt() {
        assertFalse(
            PersistedUriReadGrantPolicy.shouldReleaseAfterAttempt(
                hadPersistedReadGrantBeforeAttempt = true
            )
        )
    }

    @Test
    fun releasesReadGrantAddedOnlyForTheImportAttempt() {
        assertTrue(
            PersistedUriReadGrantPolicy.shouldReleaseAfterAttempt(
                hadPersistedReadGrantBeforeAttempt = false
            )
        )
    }

    @Test
    fun imageScreenDoesNotReleaseGrantOwnedByAnotherWorkflow() {
        assertFalse(
            PersistedUriReadGrantPolicy.shouldReleaseAfterScreen(
                hadPersistedReadGrantBeforeSelection = true,
                alreadyOwnedByScreen = false
            )
        )
    }

    @Test
    fun imageScreenReleasesGrantItAcquired() {
        assertTrue(
            PersistedUriReadGrantPolicy.shouldReleaseAfterScreen(
                hadPersistedReadGrantBeforeSelection = false,
                alreadyOwnedByScreen = false
            )
        )
    }

    @Test
    fun imageScreenRetainsOwnershipWhenSameImageIsSelectedAgain() {
        assertTrue(
            PersistedUriReadGrantPolicy.shouldReleaseAfterScreen(
                hadPersistedReadGrantBeforeSelection = true,
                alreadyOwnedByScreen = true
            )
        )
    }
}

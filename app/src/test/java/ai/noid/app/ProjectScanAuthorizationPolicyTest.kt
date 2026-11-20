package ai.noid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectScanAuthorizationPolicyTest {
    private val selectedTree =
        "content://com.android.externalstorage.documents/tree/primary%3AProjects"

    @Test
    fun allowsSelectedTreeOnlyWithGrantAndExplicitScopeConfirmation() {
        assertTrue(
            ProjectScanAuthorizationPolicy.allowsReadOnlyScan(
                selectedTreeUri = selectedTree,
                hasPersistedReadGrant = true,
                userConfirmedAuthorizedScope = true
            )
        )
    }

    @Test
    fun rejectsScanWithoutExplicitAuthorizationEvenWhenUriGrantExists() {
        assertFalse(
            ProjectScanAuthorizationPolicy.allowsReadOnlyScan(
                selectedTreeUri = selectedTree,
                hasPersistedReadGrant = true,
                userConfirmedAuthorizedScope = false
            )
        )
    }

    @Test
    fun rejectsMissingGrantAndNonContentSchemes() {
        assertFalse(
            ProjectScanAuthorizationPolicy.allowsReadOnlyScan(
                selectedTreeUri = "content://provider/tree/1",
                hasPersistedReadGrant = false,
                userConfirmedAuthorizedScope = true
            )
        )
        assertFalse(
            ProjectScanAuthorizationPolicy.allowsReadOnlyScan(
                selectedTreeUri = "file:///storage/emulated/0/Projects",
                hasPersistedReadGrant = true,
                userConfirmedAuthorizedScope = true
            )
        )
        assertFalse(
            ProjectScanAuthorizationPolicy.allowsReadOnlyScan(
                selectedTreeUri = null,
                hasPersistedReadGrant = true,
                userConfirmedAuthorizedScope = true
            )
        )
    }

    @Test
    fun rejectsMalformedAuthorityAndControlCharacters() {
        assertFalse(
            ProjectScanAuthorizationPolicy.allowsReadOnlyScan(
                selectedTreeUri = "content:///tree/1",
                hasPersistedReadGrant = true,
                userConfirmedAuthorizedScope = true
            )
        )
        assertFalse(
            ProjectScanAuthorizationPolicy.allowsReadOnlyScan(
                selectedTreeUri = "content://provider/ tree/1",
                hasPersistedReadGrant = true,
                userConfirmedAuthorizedScope = true
            )
        )
    }
}

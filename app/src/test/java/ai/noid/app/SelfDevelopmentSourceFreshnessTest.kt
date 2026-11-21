package ai.noid.app

import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SelfDevelopmentSourceFreshnessTest {
    @Test
    fun unchangedSourceMatchesOriginalSnapshot() {
        val original = "class Worker { val count = 1 }"
        val result = SelfDevelopmentSourceFreshness.verify(
            SelfDevelopmentProposalRepository.sha256(original)
        ) { original }

        assertEquals(SelfDevelopmentSourceFreshnessStatus.MATCH, result.status)
    }

    @Test
    fun changedSourceIsMarkedStale() {
        val original = "class Worker { val count = 1 }"
        val changed = "class Worker { val count = 2 }"
        val result = SelfDevelopmentSourceFreshness.verify(
            SelfDevelopmentProposalRepository.sha256(original)
        ) { changed }

        assertEquals(SelfDevelopmentSourceFreshnessStatus.STALE, result.status)
    }

    @Test
    fun revokedSafGrantFailsClosedAsUnavailable() {
        val result = SelfDevelopmentSourceFreshness.verify("a".repeat(64)) {
            throw SecurityException("permission revoked")
        }

        assertEquals(SelfDevelopmentSourceFreshnessStatus.SOURCE_UNAVAILABLE, result.status)
    }

    @Test
    fun missingDocumentFailsClosedAsUnavailable() {
        val result = SelfDevelopmentSourceFreshness.verify("a".repeat(64)) {
            throw FileNotFoundException("document missing")
        }

        assertEquals(SelfDevelopmentSourceFreshnessStatus.SOURCE_UNAVAILABLE, result.status)
    }

    @Test
    fun unreadableDocumentFailsClosedAsUnavailable() {
        val result = SelfDevelopmentSourceFreshness.verify("a".repeat(64)) {
            throw IOException("provider read failed")
        }

        assertEquals(SelfDevelopmentSourceFreshnessStatus.SOURCE_UNAVAILABLE, result.status)
    }

    @Test
    fun invalidExpectedHashDoesNotReadSource() {
        var readAttempted = false
        val result = SelfDevelopmentSourceFreshness.verify("not-a-sha256") {
            readAttempted = true
            "source"
        }

        assertEquals(SelfDevelopmentSourceFreshnessStatus.INVALID_EXPECTED_HASH, result.status)
        assertEquals(false, readAttempted)
    }

    @Test
    fun cancellationIsNotConvertedIntoAnUnavailableSource() {
        assertThrows(CancellationException::class.java) {
            SelfDevelopmentSourceFreshness.verify("a".repeat(64)) {
                throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun malformedUtf8ReplacementCannotAccidentallyMatchOriginalSnapshot() {
        val original = "valid source"
        val replacementDecodedContent = "\uFFFD"
        val result = SelfDevelopmentSourceFreshness.verify(
            SelfDevelopmentProposalRepository.sha256(original)
        ) { replacementDecodedContent }

        assertEquals(SelfDevelopmentSourceFreshnessStatus.STALE, result.status)
    }
}

package ai.noid.app

import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

enum class SelfDevelopmentSourceFreshnessStatus {
    MATCH,
    STALE,
    INVALID_EXPECTED_HASH,
    SOURCE_UNAVAILABLE
}

data class SelfDevelopmentSourceFreshnessResult(
    val status: SelfDevelopmentSourceFreshnessStatus
)

/**
 * Fail-closed check used immediately before a future approved-apply operation.
 * It exposes no source contents and never performs a write.
 */
object SelfDevelopmentSourceFreshness {
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    fun verify(
        expectedSha256: String,
        readCurrentContent: () -> String
    ): SelfDevelopmentSourceFreshnessResult {
        if (!sha256Pattern.matches(expectedSha256)) {
            return SelfDevelopmentSourceFreshnessResult(
                SelfDevelopmentSourceFreshnessStatus.INVALID_EXPECTED_HASH
            )
        }

        val currentContent = try {
            readCurrentContent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Includes revoked SAF grants, missing documents, and unreadable providers.
            return SelfDevelopmentSourceFreshnessResult(
                SelfDevelopmentSourceFreshnessStatus.SOURCE_UNAVAILABLE
            )
        }

        val actualHash = SelfDevelopmentProposalRepository.sha256(currentContent)
        val matches = MessageDigest.isEqual(
            expectedSha256.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII),
            actualHash.toByteArray(Charsets.US_ASCII)
        )
        return SelfDevelopmentSourceFreshnessResult(
            if (matches) SelfDevelopmentSourceFreshnessStatus.MATCH
            else SelfDevelopmentSourceFreshnessStatus.STALE
        )
    }
}

package ai.noid.app

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface SelfDevelopmentApplyResult {
    data class Applied(
        val backup: ProjectFileBackup,
        val completionAudited: Boolean
    ) : SelfDevelopmentApplyResult
    data object ProposalNotFound : SelfDevelopmentApplyResult
    data object NotApproved : SelfDevelopmentApplyResult
    data object AlreadyProcessing : SelfDevelopmentApplyResult
    data object ProjectNotSelected : SelfDevelopmentApplyResult
    data object ProjectSelectionChanged : SelfDevelopmentApplyResult
    data object PermissionUnavailable : SelfDevelopmentApplyResult
    data object SourceStale : SelfDevelopmentApplyResult
    data object SourceUnavailable : SelfDevelopmentApplyResult
    data object InvalidProposalSnapshot : SelfDevelopmentApplyResult
    data object AuditUnavailable : SelfDevelopmentApplyResult
    data class Rejected(val reason: String) : SelfDevelopmentApplyResult
    data class Failed(val exceptionType: String) : SelfDevelopmentApplyResult
    data object AppliedStateNotRecorded : SelfDevelopmentApplyResult
}

/**
 * Applies one approved proposal through the same permission, confirmation and
 * immutable audit boundary as other user-started file operations.
 *
 * A database claim makes the operation single-use. Cancellation leaves an
 * explicit interrupted state so an uncertain write can never be replayed blindly.
 */
class SelfDevelopmentApplyCoordinator(
    context: Context,
    private val selectedTree: SelectedProjectTreeSession,
    private val files: ProjectFileRepository,
    private val proposals: SelfDevelopmentProposalRepository,
    private val tools: ToolRegistry
) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun apply(
        proposalId: Long,
        expectedSelectionId: String
    ): SelfDevelopmentApplyResult {
        if (proposalId <= 0 || expectedSelectionId.isBlank()) {
            return SelfDevelopmentApplyResult.NotApproved
        }

        val initialSelection = selectedTree.current()
            ?: return SelfDevelopmentApplyResult.ProjectNotSelected
        if (initialSelection.selectionId != expectedSelectionId) {
            return SelfDevelopmentApplyResult.ProjectSelectionChanged
        }

        val proposal = proposals.getById(proposalId)
            ?: return SelfDevelopmentApplyResult.ProposalNotFound
        if (proposal.status != SelfDevelopmentProposal.STATUS_APPROVED) {
            return SelfDevelopmentApplyResult.NotApproved
        }
        if (!proposals.claimApprovedForApply(proposalId)) {
            return SelfDevelopmentApplyResult.AlreadyProcessing
        }

        if (!isCurrentSelection(initialSelection, expectedSelectionId)) {
            proposals.releaseApplyClaim(proposalId)
            return SelfDevelopmentApplyResult.ProjectSelectionChanged
        }
        if (!hasReadWriteGrant(initialSelection.uri)) {
            proposals.releaseApplyClaim(proposalId)
            return SelfDevelopmentApplyResult.PermissionUnavailable
        }

        if (!snapshotHashesAreValid(proposal)) {
            proposals.markApplyFailed(proposalId)
            return SelfDevelopmentApplyResult.InvalidProposalSnapshot
        }

        val action = ToolAction(
            actionId = ACTION_ID,
            capability = ToolCapability.WRITE_FILES,
            risk = ToolRisk.SENSITIVE,
            description = "تطبيق مقترح تطوير وافق عليه المستخدم بعد إعادة فحص المصدر والنسخ الاحتياطي"
        )

        val execution = try {
            tools.executeUserAction(
                action = action,
                grantedCapabilities = {
                    val active = selectedTree.current()
                    if (active?.selectionId != expectedSelectionId ||
                        active.uri != initialSelection.uri
                    ) emptySet() else currentGrantCapabilities(initialSelection.uri)
                },
                userConfirmed = true
            ) {
                if (!isCurrentSelection(initialSelection, expectedSelectionId)) {
                    throw ProjectSelectionChangedException()
                }

                // Re-read immediately before writing. A changed or unavailable source fails closed.
                when (withContext(Dispatchers.IO) {
                    files.verifySourceUnchanged(
                        initialSelection.uri,
                        proposal.relativePath,
                        proposal.originalSha256
                    )
                }.status) {
                    SelfDevelopmentSourceFreshnessStatus.MATCH -> Unit
                    SelfDevelopmentSourceFreshnessStatus.STALE -> throw StaleSourceException()
                    SelfDevelopmentSourceFreshnessStatus.SOURCE_UNAVAILABLE ->
                        throw SourceUnavailableException()
                    SelfDevelopmentSourceFreshnessStatus.INVALID_EXPECTED_HASH ->
                        throw InvalidProposalException()
                }

                if (!isCurrentSelection(initialSelection, expectedSelectionId)) {
                    throw ProjectSelectionChangedException()
                }

                val backup = withContext(Dispatchers.IO) {
                    files.writeText(
                        initialSelection.uri,
                        proposal.relativePath,
                        proposal.proposedContent
                    )
                }

                val writtenContent = withContext(Dispatchers.IO) {
                    files.readText(initialSelection.uri, proposal.relativePath)
                }
                if (SelfDevelopmentProposalRepository.sha256(writtenContent) != proposal.proposedSha256) {
                    try {
                        withContext(Dispatchers.IO) {
                            files.restoreBackup(initialSelection.uri, backup)
                        }
                    } catch (rollbackFailure: Exception) {
                        throw RollbackFailedException(rollbackFailure)
                    }
                    throw ApplyVerificationException()
                }
                backup
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { proposals.markApplyInterrupted(proposalId) }
            }
            throw cancelled
        } catch (failure: Exception) {
            proposals.markApplyFailed(proposalId)
            return SelfDevelopmentApplyResult.Failed(failure.javaClass.simpleName)
        }

        return when (execution) {
            is ToolExecutionResult.Completed -> {
                if (!proposals.markApplied(proposalId)) {
                    // The file may already be changed. Keep APPLYING to prevent a duplicate write.
                    SelfDevelopmentApplyResult.AppliedStateNotRecorded
                } else {
                    SelfDevelopmentApplyResult.Applied(
                        execution.value,
                        execution.completionAudited
                    )
                }
            }
            is ToolExecutionResult.AuditUnavailable -> {
                proposals.releaseApplyClaim(proposalId)
                SelfDevelopmentApplyResult.AuditUnavailable
            }
            is ToolExecutionResult.Rejected -> {
                proposals.releaseApplyClaim(proposalId)
                SelfDevelopmentApplyResult.Rejected(execution.reason)
            }
            is ToolExecutionResult.Failed -> when (execution.exceptionType) {
                StaleSourceException::class.java.simpleName -> {
                    proposals.markStale(proposalId)
                    SelfDevelopmentApplyResult.SourceStale
                }
                SourceUnavailableException::class.java.simpleName -> {
                    proposals.releaseApplyClaim(proposalId)
                    SelfDevelopmentApplyResult.SourceUnavailable
                }
                ProjectSelectionChangedException::class.java.simpleName -> {
                    proposals.releaseApplyClaim(proposalId)
                    SelfDevelopmentApplyResult.ProjectSelectionChanged
                }
                InvalidProposalException::class.java.simpleName -> {
                    proposals.markApplyFailed(proposalId)
                    SelfDevelopmentApplyResult.InvalidProposalSnapshot
                }
                else -> {
                    proposals.markApplyFailed(proposalId)
                    SelfDevelopmentApplyResult.Failed(execution.exceptionType)
                }
            }
        }
    }

    private fun snapshotHashesAreValid(proposal: SelfDevelopmentProposal): Boolean =
        SelfDevelopmentProposalRepository.sha256(proposal.originalContent) == proposal.originalSha256 &&
            SelfDevelopmentProposalRepository.sha256(proposal.proposedContent) == proposal.proposedSha256 &&
            proposal.originalSha256.matches(SHA256_PATTERN) &&
            proposal.proposedSha256.matches(SHA256_PATTERN)

    private fun isCurrentSelection(
        expected: SelectedProjectTreeSession.Selection,
        expectedSelectionId: String
    ): Boolean {
        val active = selectedTree.current() ?: return false
        return active.selectionId == expectedSelectionId &&
            active.selectionId == expected.selectionId &&
            active.uri == expected.uri
    }

    private fun hasReadWriteGrant(uri: Uri): Boolean {
        val grant = runCatching {
            resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        }.getOrNull() ?: return false
        return grant.isReadPermission && grant.isWritePermission
    }

    private fun currentGrantCapabilities(uri: Uri): Set<ToolCapability> {
        val grant = runCatching {
            resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        }.getOrNull() ?: return emptySet()
        return buildSet {
            if (grant.isReadPermission) add(ToolCapability.READ_FILES)
            if (grant.isWritePermission) add(ToolCapability.WRITE_FILES)
        }
    }

    private class StaleSourceException : IOException()
    private class SourceUnavailableException : IOException()
    private class ProjectSelectionChangedException : IOException()
    private class InvalidProposalException : IOException()
    private class ApplyVerificationException : IOException()
    private class RollbackFailedException(cause: Throwable) : IOException(cause)

    private companion object {
        const val ACTION_ID = "self-development-apply-approved-proposal"
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

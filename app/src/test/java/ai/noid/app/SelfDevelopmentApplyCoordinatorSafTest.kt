package ai.noid.app

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the apply coordinator through Room, ToolRegistry, audit, and the
 * ContentResolver/DocumentFile provider boundary. This is not a physical-device
 * SAF test; persisted grants are supplied by Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelfDevelopmentApplyCoordinatorSafTest {
    private val context = RuntimeEnvironment.getApplication()
    private var database: NoidDatabase? = null
    private var root: File? = null
    private var backups: File? = null
    private var treeUri: Uri? = null

    @After
    fun tearDown() {
        treeUri?.let {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        database?.close()
        root?.deleteRecursively()
        backups?.deleteRecursively()
        TestSafContentProvider.failNextWrite = false
        TestSafContentProvider.failNextRead = false
        TestSafContentProvider.cancelReadAfterNextWrite = false
        TestSafContentProvider.cancelNextRead = false
        TestSafContentProvider.writeCount = 0
    }

    @Test
    fun staleSnapshotIsRejectedBeforeAnyProviderWrite() = runBlocking {
        val fixture = fixture()
        val id = fixture.approvedProposal("before", "after")
        File(root, "Worker.kt").writeText("concurrent edit", Charsets.UTF_8)

        assertEquals(
            SelfDevelopmentApplyResult.SourceStale,
            fixture.coordinator.apply(id, fixture.selection.selectionId)
        )
        assertEquals("concurrent edit", File(root, "Worker.kt").readText(Charsets.UTF_8))
        assertEquals(0, TestSafContentProvider.writeCount)
        assertEquals(
            SelfDevelopmentProposal.STATUS_STALE,
            fixture.proposals.getById(id)?.status
        )
        assertEquals(
            listOf("started", "failed: StaleSourceException"),
            fixture.audit.events.value.reversed().map { it.details }
        )
    }

    @Test
    fun revokedPersistedSafGrantPreventsApplyAndReleasesTheClaim() = runBlocking {
        val fixture = fixture()
        val id = fixture.approvedProposal("before", "after")
        context.contentResolver.releasePersistableUriPermission(
            fixture.treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        assertEquals(
            SelfDevelopmentApplyResult.PermissionUnavailable,
            fixture.coordinator.apply(id, fixture.selection.selectionId)
        )
        assertEquals("before", File(root, "Worker.kt").readText(Charsets.UTF_8))
        assertEquals(0, TestSafContentProvider.writeCount)
        assertEquals(
            SelfDevelopmentProposal.STATUS_APPROVED,
            fixture.proposals.getById(id)?.status
        )
        assertTrue(fixture.audit.events.value.isEmpty())
    }

    @Test
    fun failedPreAuditPreventsProviderWriteAndMakesProposalRetryable() = runBlocking {
        val fixture = fixture(failAuditAtInsert = 1)
        val id = fixture.approvedProposal("before", "after")

        assertEquals(
            SelfDevelopmentApplyResult.AuditUnavailable,
            fixture.coordinator.apply(id, fixture.selection.selectionId)
        )
        assertEquals("before", File(root, "Worker.kt").readText(Charsets.UTF_8))
        assertEquals(0, TestSafContentProvider.writeCount)
        assertEquals(
            SelfDevelopmentProposal.STATUS_APPROVED,
            fixture.proposals.getById(id)?.status
        )
        assertTrue(fixture.audit.events.value.isEmpty())
    }

    @Test
    fun cancellationDuringReadBackRollsBackAndMarksApplyInterrupted() = runBlocking {
        val fixture = fixture()
        val id = fixture.approvedProposal("before", "after")
        TestSafContentProvider.cancelReadAfterNextWrite = true

        try {
            fixture.coordinator.apply(id, fixture.selection.selectionId)
            throw AssertionError("Expected cancellation to propagate")
        } catch (expected: CancellationException) {
            assertEquals("Injected provider read cancellation", expected.message)
        }

        assertEquals("before", File(root, "Worker.kt").readText(Charsets.UTF_8))
        assertEquals(
            SelfDevelopmentProposal.STATUS_APPLY_INTERRUPTED,
            fixture.proposals.getById(id)?.status
        )
        assertEquals(2, TestSafContentProvider.writeCount)
        assertEquals(
            listOf("started", "cancelled"),
            fixture.audit.events.value.reversed().map { it.details }
        )
    }

    @Test
    fun successfulApplyCannotBeReplayedByADuplicateAttempt() = runBlocking {
        val fixture = fixture()
        val id = fixture.approvedProposal("before", "after")

        val first = fixture.coordinator.apply(id, fixture.selection.selectionId)
        assertTrue(first is SelfDevelopmentApplyResult.Applied)
        assertEquals("after", File(root, "Worker.kt").readText(Charsets.UTF_8))
        assertEquals(1, TestSafContentProvider.writeCount)

        assertEquals(
            SelfDevelopmentApplyResult.NotApproved,
            fixture.coordinator.apply(id, fixture.selection.selectionId)
        )
        assertEquals("after", File(root, "Worker.kt").readText(Charsets.UTF_8))
        assertEquals(1, TestSafContentProvider.writeCount)
        assertEquals(
            SelfDevelopmentProposal.STATUS_APPLIED,
            fixture.proposals.getById(id)?.status
        )
        assertEquals(
            listOf("started", "completed"),
            fixture.audit.events.value.reversed().map { it.details }
        )
    }

    private fun fixture(failAuditAtInsert: Int? = null): Fixture {
        val projectRoot = Files.createTempDirectory("noid-apply-provider").toFile()
        val backupRoot = File(projectRoot.parentFile, "noid-apply-backups-" + System.nanoTime())
        File(projectRoot, "Worker.kt").writeText("before", Charsets.UTF_8)
        root = projectRoot
        backups = backupRoot

        TestSafContentProvider.backingRoot = projectRoot
        TestSafContentProvider.writeCount = 0
        registerTestSafContentProvider()
        val selectedUri = DocumentsContract.buildTreeDocumentUri(
            TestSafContentProvider.AUTHORITY,
            "root"
        )
        treeUri = selectedUri
        context.contentResolver.takePersistableUriPermission(
            selectedUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val db = Room.inMemoryDatabaseBuilder(
            context,
            NoidDatabase::class.java
        ).allowMainThreadQueries().build()
        database = db
        val proposals = SelfDevelopmentProposalRepository(db.selfDevelopmentProposalDao())
        val selectedTree = SelectedProjectTreeSession()
        val selection = selectedTree.select(selectedUri)
        val audit = RecordingAuditDao(failAtInsert = failAuditAtInsert)
        val registry = ToolRegistry(
            ToolExecutionCoordinator(),
            ToolAuditRepository(audit)
        )
        val coordinator = SelfDevelopmentApplyCoordinator(
            context,
            selectedTree,
            ProjectFileRepository(context, backupRoot),
            proposals,
            registry
        )
        return Fixture(selectedUri, selection, proposals, coordinator, audit)
    }

    private data class Fixture(
        val treeUri: Uri,
        val selection: SelectedProjectTreeSession.Selection,
        val proposals: SelfDevelopmentProposalRepository,
        val coordinator: SelfDevelopmentApplyCoordinator,
        val audit: RecordingAuditDao
    ) {
        suspend fun approvedProposal(original: String, proposed: String): Long {
            val id = proposals.propose(
                title = "Apply integration",
                rationale = "Verify coordinator boundaries",
                relativePath = "Worker.kt",
                originalContent = original,
                proposedContent = proposed,
                projectTreeUri = treeUri.toString()
            )
            check(proposals.approve(id))
            return id
        }
    }

    private class RecordingAuditDao(
        private val failAtInsert: Int? = null
    ) : ToolAuditDao {
        val events = MutableStateFlow<List<ToolAuditEvent>>(emptyList())
        private var insertCount = 0

        override fun observeRecent(): Flow<List<ToolAuditEvent>> = events

        override suspend fun insert(event: ToolAuditEvent): Long {
            insertCount++
            if (insertCount == failAtInsert) error("Injected audit storage failure")
            val id = insertCount.toLong()
            events.value = listOf(event.copy(id = id)) + events.value
            return id
        }
    }
}

package ai.noid.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recreates the v7 Room schema using the same entities, seeds existing rows,
 * then opens it with the production v8 database and its real migration.
 */
@Database(
    entities = [
        ChatMessage::class,
        UserMemory::class,
        ToolAuditEvent::class,
        LearningRecord::class,
        SelfDevelopmentProposal::class
    ],
    version = 7,
    exportSchema = false
)
abstract class NoidDatabaseV7Fixture : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun toolAuditDao(): ToolAuditDao
    abstract fun learningRecordDao(): LearningRecordDao
    abstract fun selfDevelopmentProposalDao(): SelfDevelopmentProposalDao
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoidDatabasePopulatedMigrationTest {
    @Test
    fun migration7To8PreservesExistingRowsAndCreatesRecoveryTable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration-v7-populated-test.db"
        context.deleteDatabase(databaseName)

        val v7 = Room.databaseBuilder(
            context,
            NoidDatabaseV7Fixture::class.java,
            databaseName
        ).allowMainThreadQueries().build()

        val chatId = v7.chatMessageDao().insert(
            ChatMessage(text = "existing chat row", fromUser = true, createdAt = 100L)
        )
        val memoryId = v7.userMemoryDao().insert(
            UserMemory(title = "existing memory", content = "preserve me", source = "test", createdAt = 101L, updatedAt = 102L)
        )
        val learningId = v7.learningRecordDao().insert(
            LearningRecord(title = "existing lesson", content = "preserve me", source = "test", createdAt = 103L)
        )
        val proposalId = v7.selfDevelopmentProposalDao().insert(
            SelfDevelopmentProposal(
                title = "existing proposal",
                rationale = "migration fixture",
                relativePath = "src/example.kt",
                projectTreeUri = "content://fixture/tree",
                originalContent = "before",
                proposedContent = "after",
                originalSha256 = "a".repeat(64),
                proposedSha256 = "b".repeat(64),
                createdAt = 104L
            )
        )
        val auditId = v7.toolAuditDao().insert(
            ToolAuditEvent(
                actionId = "fixture-action",
                actionType = "READ",
                decision = "ALLOWED",
                details = "metadata only",
                createdAt = 105L
            )
        )
        v7.close()

        val migrated = Room.databaseBuilder(
            context,
            NoidDatabase::class.java,
            databaseName
        )
            .addMigrations(NoidDatabase.MIGRATION_7_8)
            .allowMainThreadQueries()
            .build()

        try {
            assertEquals("existing chat row", migrated.chatMessageDao().getRecent(10).single { it.id == chatId }.text)
            assertEquals("preserve me", migrated.userMemoryDao().getAll().single { it.id == memoryId }.content)
            assertEquals("existing lesson", migrated.learningRecordDao().observePending().first().single { it.id == learningId }.title)
            assertEquals("content://fixture/tree", migrated.selfDevelopmentProposalDao().getById(proposalId)?.projectTreeUri)
            assertNotNull(migrated.toolAuditDao().observeRecent().first().singleOrNull { it.id == auditId })

            migrated.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM task_plan_recovery"
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }
}

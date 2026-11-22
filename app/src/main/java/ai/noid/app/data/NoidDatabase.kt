package ai.noid.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChatMessage::class, UserMemory::class, ToolAuditEvent::class, LearningRecord::class, SelfDevelopmentProposal::class, TaskPlanRecoveryRecord::class],
    version = 8,
    exportSchema = false
)
abstract class NoidDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun toolAuditDao(): ToolAuditDao
    abstract fun learningRecordDao(): LearningRecordDao
    abstract fun selfDevelopmentProposalDao(): SelfDevelopmentProposalDao
    abstract fun taskPlanRecoveryDao(): TaskPlanRecoveryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        source TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tool_audit_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        actionId TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        decision TEXT NOT NULL,
                        details TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS learning_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        source TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        reviewedAt INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_learning_records_status_reviewedAt ON learning_records(status, reviewedAt)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS self_development_proposals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        rationale TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        originalContent TEXT NOT NULL,
                        proposedContent TEXT NOT NULL,
                        originalSha256 TEXT NOT NULL,
                        proposedSha256 TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        reviewedAt INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_self_development_proposals_status_createdAt ON self_development_proposals(status, createdAt)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE self_development_proposals ADD COLUMN projectTreeUri TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE learning_records ADD COLUMN deduplicationKey TEXT"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_learning_records_deduplicationKey ON learning_records(deduplicationKey)"
                )
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS task_plan_recovery (
                        planId TEXT NOT NULL PRIMARY KEY,
                        stepCount INTEGER NOT NULL,
                        currentStepIndex INTEGER NOT NULL,
                        currentStepId TEXT,
                        state TEXT NOT NULL,
                        dispatchStarted INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        @Volatile
        private var INSTANCE: NoidDatabase? = null

        fun getInstance(context: Context): NoidDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NoidDatabase::class.java,
                    "noid-local.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

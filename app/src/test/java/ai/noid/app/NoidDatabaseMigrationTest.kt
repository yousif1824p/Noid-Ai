package ai.noid.app

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class NoidDatabaseMigrationTest {
    @Test fun migration7To8CreatesBoundedMetadataOnlyRecoveryTable() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                statements += args?.getOrNull(0) as String
                null
            } else {
                throw UnsupportedOperationException("Unexpected database call: ${method.name}")
            }
        } as SupportSQLiteDatabase

        assertEquals(7, NoidDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, NoidDatabase.MIGRATION_7_8.endVersion)
        NoidDatabase.MIGRATION_7_8.migrate(database)

        assertEquals(1, statements.size)
        val sql = statements.single()
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS task_plan_recovery"))
        listOf(
            "planId TEXT NOT NULL PRIMARY KEY",
            "stepCount INTEGER NOT NULL",
            "currentStepIndex INTEGER NOT NULL",
            "currentStepId TEXT",
            "state TEXT NOT NULL",
            "dispatchStarted INTEGER NOT NULL",
            "createdAt INTEGER NOT NULL",
            "updatedAt INTEGER NOT NULL"
        ).forEach { column -> assertTrue("Missing migration column: $column", sql.contains(column)) }

        // Recovery rows intentionally exclude arguments, approval tickets, and tool output.
        listOf("arguments", "approvalTicket", "rawOutput", "credential").forEach {
            assertTrue("Unexpected sensitive field in migration: $it", !sql.contains(it, ignoreCase = true))
        }
    }
}

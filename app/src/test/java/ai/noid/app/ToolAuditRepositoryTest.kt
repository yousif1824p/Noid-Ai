package ai.noid.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAuditRepositoryTest {
    @Test
    fun recordPersistsActionAndAuthorizationDetails() = runBlocking {
        val dao = FakeToolAuditDao()
        val repository = ToolAuditRepository(dao)
        val action = ToolAction(
            actionId = "save-project-file",
            capability = ToolCapability.WRITE_FILES,
            risk = ToolRisk.REVERSIBLE,
            description = "حفظ ملف في المشروع المحدد"
        )
        val authorization = ToolAuthorization(ToolDecision.ALLOW, "موافقة صالحة")

        assertTrue(repository.record(action, authorization, "src/Main.kt"))

        val saved = dao.events.value.single()
        assertEquals("save-project-file", saved.actionId)
        assertEquals(ToolCapability.WRITE_FILES.name, saved.actionType)
        assertEquals(ToolDecision.ALLOW.name, saved.decision)
        assertEquals("src/Main.kt", saved.details)
    }

    @Test
    fun auditDetailsRedactBearerTokensAndNamedSecretsBeforePersistence() = runBlocking {
        val dao = FakeToolAuditDao()
        val repository = ToolAuditRepository(dao)
        val action = ToolAction("remote-request", ToolCapability.READ_SCREEN, ToolRisk.READ_ONLY, "طلب بعيد")
        val details = "Authorization: Bearer bearer-value api_key=key-value password='pass-value'"

        assertTrue(repository.record(action, ToolAuthorization(ToolDecision.ALLOW, "مسموح"), details))

        val saved = dao.events.value.single().details.orEmpty()
        assertFalse(saved.contains("bearer-value"))
        assertFalse(saved.contains("key-value"))
        assertFalse(saved.contains("pass-value"))
        assertTrue(saved.contains("[REDACTED]"))
    }

    @Test
    fun auditDetailsRedactQueryCredentialsJwtAndUrlUserInfo() = runBlocking {
        val dao = FakeToolAuditDao()
        val repository = ToolAuditRepository(dao)
        val action = ToolAction("remote-request", ToolCapability.READ_SCREEN, ToolRisk.READ_ONLY, "طلب بعيد")
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signaturevalue"
        val details = "https://user:password@example.test/path?access_token=query-secret jwt=$token"

        assertTrue(repository.record(action, ToolAuthorization(ToolDecision.ALLOW, "مسموح"), details))

        val saved = dao.events.value.single().details.orEmpty()
        listOf("user:password", "query-secret", token).forEach { assertFalse(saved.contains(it)) }
        assertTrue(saved.contains("[REDACTED_JWT]"))
    }

    @Test
    fun recordBoundsSanitizedStoredDetailsToOneThousandCharacters() = runBlocking {
        val dao = FakeToolAuditDao()
        val repository = ToolAuditRepository(dao)
        val action = ToolAction("scan-1", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "فحص ملف")
        val longDetails = "x".repeat(1500)

        assertTrue(repository.record(action, ToolAuthorization(ToolDecision.ALLOW, "مسموح"), longDetails))
        assertEquals(1000, dao.events.value.single().details?.length)
    }

    @Test
    fun sanitizerAcceptsNullAndZeroLengthBounds() {
        assertNull(ToolAuditDetailsSanitizer.sanitize(null, 10))
        assertEquals("", ToolAuditDetailsSanitizer.sanitize("secret=value", 0))
    }

    @Test
    fun recordReturnsFalseWhenDatabaseInsertFails() = runBlocking {
        val dao = FakeToolAuditDao(failInsert = true)
        val repository = ToolAuditRepository(dao)
        val action = ToolAction("read-1", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "قراءة ملف")

        assertFalse(repository.record(action, ToolAuthorization(ToolDecision.ALLOW, "مسموح"), "file.txt"))
        assertTrue(dao.events.value.isEmpty())
    }

    @Test
    fun recordPropagatesCoroutineCancellation() = runBlocking {
        val dao = FakeToolAuditDao(cancelInsert = true)
        val repository = ToolAuditRepository(dao)
        val action = ToolAction("read-1", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "قراءة ملف")

        try {
            repository.record(action, ToolAuthorization(ToolDecision.ALLOW, "مسموح"), "file.txt")
            throw AssertionError("Expected cancellation to propagate")
        } catch (expected: CancellationException) {
            assertEquals("simulated cancellation", expected.message)
        }
    }

    private class FakeToolAuditDao(
        private val failInsert: Boolean = false,
        private val cancelInsert: Boolean = false
    ) : ToolAuditDao {
        val events = MutableStateFlow<List<ToolAuditEvent>>(emptyList())

        override fun observeRecent(): Flow<List<ToolAuditEvent>> = events

        override suspend fun insert(event: ToolAuditEvent): Long {
            if (cancelInsert) throw CancellationException("simulated cancellation")
            if (failInsert) error("simulated database failure")
            val nextId = (events.value.maxOfOrNull { it.id } ?: 0L) + 1L
            events.value = listOf(event.copy(id = nextId)) + events.value
            return nextId
        }
    }
}

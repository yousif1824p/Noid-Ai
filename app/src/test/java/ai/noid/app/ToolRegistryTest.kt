package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolRegistryTest {
    private val action = ToolAction(
        "project.read-text",
        ToolCapability.READ_FILES,
        ToolRisk.READ_ONLY,
        "قراءة ملف نصي ضمن مشروع اختاره المستخدم"
    )

    @Test
    fun userActionGatewayRejectsMissingGrantAndAuditsWithoutExecuting() = runBlocking {
        val dao = FakeAuditDao()
        val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao))
        var calls = 0

        val result = registry.executeUserAction(
            action = action,
            grantedCapabilities = { emptySet() },
            userConfirmed = false
        ) {
            calls++
            "must not run"
        }

        assertEquals(
            ToolExecutionResult.Rejected(
                ToolDecision.DENY,
                "الصلاحية المطلوبة غير ممنوحة.",
                auditRecorded = true
            ),
            result
        )
        assertEquals(0, calls)
        assertEquals(listOf("blocked: DENY"), dao.events.value.map { it.details })
    }

    @Test
    fun userActionGatewayRequiresConfirmationForSensitiveActions() = runBlocking {
        val sensitive = action.copy(risk = ToolRisk.SENSITIVE)
        val dao = FakeAuditDao()
        val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao))
        var calls = 0

        val result = registry.executeUserAction(
            action = sensitive,
            grantedCapabilities = { setOf(ToolCapability.READ_FILES) },
            userConfirmed = false
        ) {
            calls++
            "must not run"
        }

        assertTrue(result is ToolExecutionResult.Rejected)
        assertEquals(ToolDecision.REQUIRE_USER_CONFIRMATION, (result as ToolExecutionResult.Rejected).decision)
        assertEquals(0, calls)
        assertEquals("blocked: REQUIRE_USER_CONFIRMATION", dao.events.value.single().details)
    }

    @Test
    fun userActionGatewayFailsClosedWhenStartAuditCannotBePersisted() = runBlocking {
        val dao = FakeAuditDao(failAtInsert = 1)
        val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao))
        var calls = 0

        val result = registry.executeUserAction(
            action = action,
            grantedCapabilities = { setOf(ToolCapability.READ_FILES) },
            userConfirmed = false
        ) {
            calls++
            "must not run"
        }

        assertEquals(ToolExecutionResult.AuditUnavailable("start"), result)
        assertEquals(0, calls)
        assertTrue(dao.events.value.isEmpty())
    }

    @Test
    fun userActionGatewayRecordsSuccessfulCompletion() = runBlocking {
        val dao = FakeAuditDao()
        val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao))

        val result = registry.executeUserAction(
            action = action,
            grantedCapabilities = { setOf(ToolCapability.READ_FILES) },
            userConfirmed = false
        ) { "listed" }

        assertEquals(ToolExecutionResult.Completed("listed", true), result)
        assertEquals(listOf("started", "completed"), dao.events.value.reversed().map { it.details })
    }

    @Test
    fun userActionGatewayRecordsFailureWithoutLeakingExceptionMessage() = runBlocking {
        val dao = FakeAuditDao()
        val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao))

        val result = registry.executeUserAction(
            action = action,
            grantedCapabilities = { setOf(ToolCapability.READ_FILES) },
            userConfirmed = false
        ) { error("private path and file contents") }

        assertEquals(ToolExecutionResult.Failed("IllegalStateException", true), result)
        assertEquals(listOf("started", "failed: IllegalStateException"), dao.events.value.reversed().map { it.details })
        assertTrue(dao.events.value.none { it.details?.contains("private path") == true })
    }

    @Test
    fun userActionGatewayPropagatesCancellationAfterTerminalAudit() = runBlocking {
        val dao = FakeAuditDao()
        val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao))

        try {
            registry.executeUserAction(
                action = action,
                grantedCapabilities = { setOf(ToolCapability.READ_FILES) },
                userConfirmed = false
            ) { throw kotlinx.coroutines.CancellationException("user stopped") }
            fail("Expected cancellation to propagate")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            assertEquals("user stopped", expected.message)
        }

        assertEquals(listOf("started", "cancelled"), dao.events.value.reversed().map { it.details })
    }

    @Test
    fun unknownToolIsNeverExecuted() = runBlocking {
        val registry = registry()
        val result = registry.invoke("not-registered", ToolRequest(emptyMap()))
        assertEquals(ToolRegistryResult.UnknownTool, result)
    }

    @Test
    fun registeredToolRunsOnlyWithGrantedCapability() = runBlocking {
        var calls = 0
        var platformGrant: Set<ToolCapability> = emptySet()
        val registry = registry().also {
            it.register(adapter(grants = { platformGrant }, execute = { calls++; "contents" }))
        }
        val request = ToolRequest(emptyMap())
        val denied = registry.invoke(action.actionId, request)
        assertTrue(denied is ToolRegistryResult.Executed)
        assertTrue((denied as ToolRegistryResult.Executed).result is ToolExecutionResult.Rejected)
        assertEquals(0, calls)

        platformGrant = setOf(ToolCapability.READ_FILES)
        val allowed = registry.invoke(action.actionId, request)
        assertEquals(ToolExecutionResult.Completed("contents", true), (allowed as ToolRegistryResult.Executed).result)
        assertEquals(1, calls)
    }

    @Test
    fun failedPreExecutionAuditPreventsAdapterExecutionThroughRegistry() = runBlocking {
        var calls = 0
        val registry = ToolRegistry(
            ToolExecutionCoordinator(),
            ToolAuditRepository(FakeAuditDao(failAtInsert = 1))
        ).also {
            it.register(adapter(execute = { calls++; "must not run" }))
        }

        val result = registry.invoke(
            action.actionId,
            ToolRequest(emptyMap())
        )

        assertTrue(result is ToolRegistryResult.Executed)
        assertEquals(
            ToolExecutionResult.AuditUnavailable("start"),
            (result as ToolRegistryResult.Executed).result
        )
        assertEquals(0, calls)
    }

    @Test
    fun registeredAdapterFailureIsReturnedAndAuditedWithoutLeakingExceptionMessage() = runBlocking {
        val dao = FakeAuditDao()
        val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao)).also {
            it.register(adapter(execute = { error("private file path and contents") }))
        }

        val result = registry.invoke(
            action.actionId,
            ToolRequest(emptyMap())
        ) as ToolRegistryResult.Executed

        assertEquals(ToolExecutionResult.Failed("IllegalStateException", true), result.result)
        assertEquals(
            listOf("started", "failed: IllegalStateException"),
            dao.events.value.reversed().map { it.details }
        )
        assertTrue(dao.events.value.none { it.details?.contains("private file path") == true })
    }

    @Test
    fun registeredAdapterCancellationPropagatesAndWritesTerminalAuditEvent() = runBlocking {
        val dao = FakeAuditDao()
        val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao)).also {
            it.register(adapter(execute = { throw kotlinx.coroutines.CancellationException("user stopped") }))
        }

        try {
            registry.invoke(
                action.actionId,
                ToolRequest(emptyMap())
            )
            fail("Expected cancellation to propagate")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            assertEquals("user stopped", expected.message)
        }

        assertEquals(
            listOf("started", "cancelled"),
            dao.events.value.reversed().map { it.details }
        )
    }

    @Test
    fun validationFailurePreventsToolExecution() = runBlocking {
        var calls = 0
        val registry = registry().also {
            it.register(adapter(execute = { calls++; "should not run" }, validate = { "مسار غير مسموح" }))
        }
        val result = registry.invoke(action.actionId, ToolRequest(emptyMap()))
        assertEquals(ToolRegistryResult.InvalidRequest("مسار غير مسموح"), result)
        assertEquals(0, calls)
    }

    @Test
    fun requestDefensivelyCopiesAndProtectsArguments() {
        val original = mutableMapOf("path" to "safe/file.txt")
        val request = ToolRequest(original)
        original["path"] = "changed-after-construction"
        assertEquals("safe/file.txt", request.arguments["path"])
        try {
            (request.arguments as MutableMap<String, String>)["path"] = "mutated"
            fail("Request arguments must be immutable.")
        } catch (_: UnsupportedOperationException) {
            assertEquals("safe/file.txt", request.arguments["path"])
        }
    }

    @Test
    fun oversizedArgumentsAreRejectedBeforeAdapterValidation() = runBlocking {
        var validations = 0
        val registry = registry().also { it.register(adapter(validate = { validations++; null })) }
        val request = ToolRequest(mapOf("large" to "x".repeat(ToolRequest.MAX_VALUE_LENGTH + 1)))
        val result = registry.invoke(action.actionId, request)
        assertTrue(result is ToolRegistryResult.InvalidRequest)
        assertEquals(0, validations)
    }

    @Test
    fun sensitiveToolRequiresUiApprovedBoundOneTimeRequest() = runBlocking {
        var calls = 0
        val sensitive = action.copy(actionId = "project.write-text", capability = ToolCapability.WRITE_FILES, risk = ToolRisk.SENSITIVE)
        val registry = registry().also { it.register(adapter(sensitive, execute = { calls++; "saved" })) }
        val request = ToolRequest(mapOf("path" to "notes.txt", "content" to "hello"))
        assertEquals(ToolRegistryResult.ApprovalRequired, registry.invoke(sensitive.actionId, request))
        assertEquals(0, calls)
        val pending = registry.requestApproval(sensitive.actionId, request)!!
        val changed = ToolRequest(mapOf("path" to "other.txt", "content" to "hello"))
        assertEquals(ToolRegistryResult.ApprovalRequired, registry.invoke(sensitive.actionId, changed, pending.id))
        assertEquals(0, calls)
        assertTrue(registry.approveFromUser(pending.id))
        val result = registry.invoke(sensitive.actionId, request, pending.id)
        assertTrue((result as ToolRegistryResult.Executed).result is ToolExecutionResult.Completed)
        assertEquals(1, calls)
        assertEquals(ToolRegistryResult.ApprovalRequired, registry.invoke(sensitive.actionId, request, pending.id))
        assertEquals(1, calls)
    }

    @Test
    fun approvalButtonPathInvokesOnlyTheStoredRequestWithAdapterDerivedGrants() = runBlocking {
        var calls = 0
        var executedPath: String? = null
        val sensitive = action.copy(actionId = "project.write-text", capability = ToolCapability.WRITE_FILES, risk = ToolRisk.SENSITIVE)
        val registry = registry().also {
            it.register(object : NoidToolAdapter {
                override val action = sensitive
                override fun validate(request: ToolRequest): String? =
                    if (request.arguments["path"]?.endsWith(".txt") == true) null else "مسار غير صالح"
                override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> = setOf(ToolCapability.WRITE_FILES)
                override suspend fun execute(request: ToolRequest): String {
                    calls++
                    executedPath = request.arguments["path"]
                    return "saved"
                }
            })
        }
        val pending = registry.requestApproval(sensitive.actionId, ToolRequest(mapOf("path" to "approved.txt")))!!
        val result = registry.approveAndInvoke(pending.id)
        assertEquals(ToolExecutionResult.Completed("saved", true), (result as ToolRegistryResult.Executed).result)
        assertEquals("approved.txt", executedPath)
        assertEquals(1, calls)
        assertTrue(registry.pendingApprovals.value.isEmpty())
        assertEquals(ToolRegistryResult.ApprovalRequired, registry.approveAndInvoke(pending.id))
        assertEquals(1, calls)
    }

    @Test
    fun approvalDoesNotBypassMissingPlatformCapability() = runBlocking {
        var calls = 0
        val sensitive = action.copy(actionId = "project.write-text", capability = ToolCapability.WRITE_FILES, risk = ToolRisk.SENSITIVE)
        var platformGrant: Set<ToolCapability> = setOf(ToolCapability.WRITE_FILES)
        val registry = registry().also {
            it.register(adapter(sensitive, grants = { platformGrant }, execute = { calls++; "must not run" }))
        }
        val pending = registry.requestApproval(sensitive.actionId, ToolRequest(mapOf("path" to "notes.txt")))!!
        // The request is created while the grant exists; revoke it before execution.
        platformGrant = emptySet()
        val result = registry.approveAndInvoke(pending.id)
        assertTrue((result as ToolRegistryResult.Executed).result is ToolExecutionResult.Rejected)
        assertEquals(0, calls)
    }

    @Test
    fun approvedTicketIsHiddenFromPendingUiAndCanStillBeRevoked() {
        val sensitive = action.copy(actionId = "project.write-text", capability = ToolCapability.WRITE_FILES, risk = ToolRisk.SENSITIVE)
        val registry = registry().also { it.register(adapter(sensitive)) }
        val pending = registry.requestApproval(sensitive.actionId, ToolRequest(mapOf("path" to "notes.txt", "content" to "hello")))!!
        assertEquals(listOf(pending), registry.pendingApprovals.value)
        assertTrue(registry.approveFromUser(pending.id))
        assertTrue(registry.pendingApprovals.value.isEmpty())
        assertTrue(registry.cancelApproval(pending.id))
        assertFalse(registry.cancelApproval(pending.id))
    }

    @Test
    fun approvalQueueOverflowFailsClosedWithoutCrashingTheUi() {
        val sensitive = action.copy(actionId = "project.write-text", capability = ToolCapability.WRITE_FILES, risk = ToolRisk.SENSITIVE)
        val registry = registry().also { it.register(adapter(sensitive)) }
        repeat(8) { index ->
            assertTrue(registry.requestApproval(sensitive.actionId, ToolRequest(mapOf("path" to "$index.txt"))) != null)
        }
        assertEquals(null, registry.requestApproval(sensitive.actionId, ToolRequest(mapOf("path" to "overflow.txt"))))
        assertEquals(8, registry.pendingApprovals.value.size)
    }

    @Test
    fun duplicateRegistrationIsRejected() {
        val registry = registry()
        registry.register(adapter())
        try {
            registry.register(adapter())
            throw AssertionError("Expected duplicate registration to fail")
        } catch (_: IllegalArgumentException) {
            assertEquals(setOf(action.actionId), registry.registeredActionIds())
        }
    }

    @Test
    fun everyUserWorkflowActionRequiresItsExactCapabilityAndUsesTheRegistryGateway() = runBlocking {
        assertEquals(12, NoidUserToolActions.all.size)
        assertEquals(12, NoidUserToolActions.all.map { it.actionId }.toSet().size)
        NoidUserToolActions.all.forEach { action ->
            val registry = registry()
            var calls = 0
            val denied = registry.executeUserAction(
                action = action,
                grantedCapabilities = { emptySet() },
                userConfirmed = action.risk == ToolRisk.SENSITIVE
            ) { calls++; "must not execute" }
            assertTrue("Missing grant must reject ${action.actionId}", denied is ToolExecutionResult.Rejected)
            assertEquals("Missing grant executed ${action.actionId}", 0, calls)
            val allowed = registry.executeUserAction(
                action = action,
                grantedCapabilities = { setOf(action.capability) },
                userConfirmed = action.risk == ToolRisk.SENSITIVE
            ) { calls++; action.actionId }
            assertEquals("Authorized action did not complete: ${action.actionId}",
                ToolExecutionResult.Completed(action.actionId, true), allowed)
            assertEquals("Unexpected operation count for ${action.actionId}", 1, calls)
        }
    }

    @Test
    fun userActionGatewayUsesCurrentGrantSupplierForEachInvocation() = runBlocking {
        val registry = registry()
        var grant: Set<ToolCapability> = setOf(ToolCapability.READ_FILES)
        var checks = 0
        var calls = 0
        val first = registry.executeUserAction(
            action = action,
            grantedCapabilities = { checks++; grant },
            userConfirmed = false
        ) { calls++; "first" }
        assertEquals(ToolExecutionResult.Completed("first", true), first)
        grant = emptySet()
        val second = registry.executeUserAction(
            action = action,
            grantedCapabilities = { checks++; grant },
            userConfirmed = false
        ) { calls++; "must not run" }
        assertEquals(2, checks)
        assertEquals(1, calls)
        assertTrue(second is ToolExecutionResult.Rejected)
    }

    @Test
    fun userActionGatewayTreatsGrantLookupFailureAsNoCapability() = runBlocking {
        val registry = registry()
        var calls = 0
        val result = registry.executeUserAction(
            action = action,
            grantedCapabilities = { error("permission query failed") },
            userConfirmed = false
        ) { calls++; "must not run" }
        assertTrue(result is ToolExecutionResult.Rejected)
        assertEquals(0, calls)
    }

    @Test
    fun userActionGatewayPropagatesCancellationDuringGrantLookup() = runBlocking {
        val dao = FakeAuditDao()
        val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao))
        var calls = 0

        try {
            registry.executeUserAction(
                action = action,
                grantedCapabilities = { throw kotlinx.coroutines.CancellationException("permission lookup cancelled") },
                userConfirmed = false
            ) { calls++; "must not run" }
            fail("Expected grant lookup cancellation to propagate")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            assertEquals("permission lookup cancelled", expected.message)
        }

        assertEquals(0, calls)
        assertTrue(dao.events.value.isEmpty())
    }

    @Test
    fun registeredAdapterPropagatesCancellationDuringGrantLookup() = runBlocking {
        val dao = FakeAuditDao()
        val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao)).also {
            it.register(adapter(grants = { throw kotlinx.coroutines.CancellationException("adapter grant lookup cancelled") }))
        }

        try {
            registry.invoke(action.actionId, ToolRequest(emptyMap()))
            fail("Expected adapter grant lookup cancellation to propagate")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            assertEquals("adapter grant lookup cancelled", expected.message)
        }

        assertTrue(dao.events.value.isEmpty())
    }

    @Test
    fun everyUserWorkflowActionFailsClosedWhenStartAuditCannotBePersisted() = runBlocking {
        NoidUserToolActions.all.forEach { action ->
            val dao = FakeAuditDao(failAtInsert = 1)
            val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao))
            var calls = 0
            val result = registry.executeUserAction(
                action = action,
                grantedCapabilities = { setOf(action.capability) },
                userConfirmed = action.risk == ToolRisk.SENSITIVE || action.risk == ToolRisk.DESTRUCTIVE
            ) { calls++; "must not execute" }
            assertEquals("Audit start must fail closed for ${action.actionId}",
                ToolExecutionResult.AuditUnavailable("start"), result)
            assertEquals("Audit failure executed ${action.actionId}", 0, calls)
            assertTrue("Failed start audit left events for ${action.actionId}", dao.events.value.isEmpty())
        }
    }

    @Test
    fun everyUserWorkflowActionAuditsOperationFailureWithoutLeakingDetails() = runBlocking {
        NoidUserToolActions.all.forEach { action ->
            val dao = FakeAuditDao()
            val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao))
            val result = registry.executeUserAction(
                action = action,
                grantedCapabilities = { setOf(action.capability) },
                userConfirmed = action.risk == ToolRisk.SENSITIVE || action.risk == ToolRisk.DESTRUCTIVE
            ) { error("private path and contents for ${action.actionId}") }
            assertEquals("Failure was not audited for ${action.actionId}",
                ToolExecutionResult.Failed("IllegalStateException", true), result)
            assertEquals("started", dao.events.value.last().details)
            assertEquals("failed: IllegalStateException", dao.events.value.first().details)
            assertTrue("Sensitive details leaked for ${action.actionId}",
                dao.events.value.none { it.details?.contains("private path") == true })
        }
    }

    @Test
    fun everyUserWorkflowActionAuditsAndPropagatesCancellation() = runBlocking {
        NoidUserToolActions.all.forEach { action ->
            val dao = FakeAuditDao()
            val registry = ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao))
            try {
                registry.executeUserAction(
                    action = action,
                    grantedCapabilities = { setOf(action.capability) },
                    userConfirmed = action.risk == ToolRisk.SENSITIVE || action.risk == ToolRisk.DESTRUCTIVE
                ) { throw kotlinx.coroutines.CancellationException("user stopped") }
                fail("Expected cancellation for ${action.actionId}")
            } catch (expected: kotlinx.coroutines.CancellationException) {
                assertEquals("user stopped", expected.message)
            }
            assertEquals("started", dao.events.value.last().details)
            assertEquals("cancelled", dao.events.value.first().details)
        }
    }

    @Test
    fun requestLimitsAcceptExactBoundariesAndRejectEachOverflow() {
        val maxArguments = (1..ToolRequest.MAX_ARGUMENTS).associate { "k$it" to "v" }
        assertTrue(ToolRequest(maxArguments).isWithinLimits())
        assertTrue(ToolRequest(mapOf("k".repeat(ToolRequest.MAX_KEY_LENGTH) to "v")).isWithinLimits())
        assertTrue(ToolRequest(mapOf("k" to "v".repeat(ToolRequest.MAX_VALUE_LENGTH))).isWithinLimits())

        assertFalse(ToolRequest((1..(ToolRequest.MAX_ARGUMENTS + 1)).associate { "k$it" to "v" }).isWithinLimits())
        assertFalse(ToolRequest(mapOf("k".repeat(ToolRequest.MAX_KEY_LENGTH + 1) to "v")).isWithinLimits())
        assertFalse(ToolRequest(mapOf("k" to "v".repeat(ToolRequest.MAX_VALUE_LENGTH + 1))).isWithinLimits())
        assertFalse(ToolRequest(mapOf("   " to "v")).isWithinLimits())
        assertFalse(ToolRequest(mapOf("bad\nkey" to "v")).isWithinLimits())
        assertFalse(ToolRequest(mapOf("hidden\u202Ekey" to "v")).isWithinLimits())
        assertTrue(ToolRequest(mapOf("content" to "line 1\nline 2")).isWithinLimits())
    }

    @Test
    fun requestArgumentsRemainBoundedWhenKeysAndValuesAreMixed() {
        val request = ToolRequest(
            mapOf(
                "path" to "project/src/Main.kt",
                "operation" to "read",
                "description" to "x".repeat(128)
            )
        )
        assertTrue(request.isWithinLimits())
        assertEquals(3, request.arguments.size)
    }

    private fun registry(dao: ToolAuditDao = FakeAuditDao()): ToolRegistry =
        ToolRegistry(ToolExecutionCoordinator(), ToolAuditRepository(dao))

    private fun adapter(
        toolAction: ToolAction = action,
        grants: (ToolRequest) -> Set<ToolCapability> = { setOf(toolAction.capability) },
        execute: suspend () -> String = { "ok" },
        validate: (ToolRequest) -> String? = { null }
    ): NoidToolAdapter = object : NoidToolAdapter {
        override val action = toolAction
        override fun validate(request: ToolRequest): String? = validate.invoke(request)
        override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> = grants(request)
        override suspend fun execute(request: ToolRequest): String = execute.invoke()
    }

    private class FakeAuditDao(
        private val failAtInsert: Int? = null
    ) : ToolAuditDao {
        val events = MutableStateFlow<List<ToolAuditEvent>>(emptyList())
        private var inserts = 0
        override fun observeRecent(): Flow<List<ToolAuditEvent>> = events
        override suspend fun insert(event: ToolAuditEvent): Long {
            inserts++
            if (inserts == failAtInsert) error("database unavailable")
            val id = events.value.size + 1L
            events.value = listOf(event.copy(id = id)) + events.value
            return id
        }
    }
}

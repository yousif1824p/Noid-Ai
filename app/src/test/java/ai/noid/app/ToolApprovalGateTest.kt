package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolApprovalGateTest {
    private val action = ToolAction(
        "project.delete", ToolCapability.DELETE_DATA, ToolRisk.DESTRUCTIVE,
        "حذف ملف وافق المستخدم على اختياره"
    )

    @Test
    fun approvalIsBoundToActionAndExactArgumentsAndConsumedOnce() {
        val gate = ToolApprovalGate()
        val request = ToolRequest(mapOf("path" to "a.txt"))
        val pending = gate.createPending(action, request)
        assertFalse(gate.consume(pending.id, action, request))
        assertTrue(gate.approve(pending.id))
        assertFalse(gate.consume(pending.id, action.copy(actionId = "other"), request))
        assertFalse(gate.consume(pending.id, action, request))
        assertFalse(gate.consume(pending.id, action, request))
    }

    @Test
    fun pendingArgumentsAreImmutableAndCannotChangeTheApprovedRequest() {
        val gate = ToolApprovalGate()
        val request = ToolRequest(mapOf("path" to "safe.txt"))
        val pending = gate.createPending(action, request)
        try {
            (pending.arguments as MutableMap<String, String>)["path"] = "other.txt"
            fail("Pending approval arguments must be immutable.")
        } catch (_: UnsupportedOperationException) {
            assertEquals("safe.txt", pending.arguments["path"])
        }
        assertTrue(gate.approve(pending.id))
        assertTrue(gate.consume(pending.id, action, request))
    }

    @Test
    fun expiredApprovalCannotBeUsed() {
        var now = 10_000L
        val gate = ToolApprovalGate(clock = { now }, ttlMillis = 100L)
        val request = ToolRequest(mapOf("path" to "a.txt"))
        val pending = gate.createPending(action, request)
        assertTrue(gate.approve(pending.id))
        now += 101L
        assertFalse(gate.consume(pending.id, action, request))
    }

    @Test
    fun cancellationPreventsApproval() {
        val gate = ToolApprovalGate()
        val pending = gate.createPending(action, ToolRequest(emptyMap()))
        assertTrue(gate.cancel(pending.id))
        assertFalse(gate.approve(pending.id))
    }

    @Test
    fun pendingApprovalQueueIsBoundedAndExpiredEntriesArePurgedBeforeCapacityCheck() {
        var now = 1_000L
        val gate = ToolApprovalGate(clock = { now }, ttlMillis = 100L)
        repeat(8) { index ->
            gate.createPending(action, ToolRequest(mapOf("path" to "$index.txt")))
        }
        try {
            gate.createPending(action, ToolRequest(mapOf("path" to "overflow.txt")))
            fail("The pending approval queue must be bounded.")
        } catch (_: IllegalStateException) {
            assertEquals(8, gate.pendingApprovals().size)
        }
        now += 101L
        val replacement = gate.createPending(action, ToolRequest(mapOf("path" to "fresh.txt")))
        assertEquals("fresh.txt", replacement.arguments["path"])
        assertEquals(1, gate.pendingApprovals().size)
    }
}

package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallResponseJsonTest {
    @Test
    fun escapesQuotesSlashesAndControlCharacters() {
        assertEquals(
            "{\"ok\":true,\"content\":\"line\\n\\\"quoted\\\"\\\\path\"}",
            ToolCallResponseJson.success("line\n\"quoted\"\\path")
        )
    }

    @Test
    fun encodesRejectedToolResultAsFailure() {
        assertEquals(
            "{\"ok\":false,\"error\":\"رفض المستخدم\"}",
            ToolCallResponseJson.failure("رفض المستخدم")
        )
    }
}
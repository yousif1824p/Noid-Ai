package ai.noid.app

object ToolCallResponseJson {
    fun success(content: String): String =
        "{\"ok\":true,\"content\":\"${escape(content)}\"}"
    fun failure(reason: String): String =
        "{\"ok\":false,\"error\":\"${escape(reason)}\"}"
    fun fromRegistryResult(result: ToolRegistryResult): String = when (result) {
        is ToolRegistryResult.Executed -> when (val execution = result.result) {
            is ToolExecutionResult.Completed -> success(execution.value)
            is ToolExecutionResult.Rejected -> failure(execution.reason)
            is ToolExecutionResult.AuditUnavailable -> failure("سجل التدقيق غير متاح؛ لم يُنفذ الإجراء.")
            is ToolExecutionResult.Failed -> failure("فشل التنفيذ: ${execution.exceptionType}")
        }
        ToolRegistryResult.UnknownTool -> failure("الأداة غير مسجلة.")
        is ToolRegistryResult.InvalidRequest -> failure(result.reason)
        ToolRegistryResult.ApprovalRequired -> failure("الموافقة مفقودة أو انتهت صلاحيتها.")
    }
    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append(' ') else append(char)
            }
        }
    }
}
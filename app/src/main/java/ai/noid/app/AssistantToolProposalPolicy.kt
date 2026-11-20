package ai.noid.app

/**
 * Untrusted, model-originated intent only. It carries no URI, capability,
 * permission, approval, audit identifier, or executable payload.
 */
data class AssistantToolProposal(
    val actionId: String,
    val arguments: Map<String, String>,
    val explanation: String = ""
)

sealed interface AssistantToolProposalValidation {
    data class Accepted(val request: ToolRequest, val explanation: String) :
        AssistantToolProposalValidation
    data class Rejected(val reason: String) : AssistantToolProposalValidation
}

/** Fail-closed validation before a proposal can be shown as a pending approval. */
object AssistantToolProposalPolicy {
    private val forbiddenArgumentNames = setOf(
        "uri", "contenturi", "permission", "permissions", "capability", "capabilities",
        "approved", "approvalid", "auditid", "filesystemhandle", "shell", "shellcommand",
        "command", "code", "script", "executable", "payload"
    )

    fun validate(
        proposal: AssistantToolProposal,
        registeredActionIds: Set<String>
    ): AssistantToolProposalValidation {
        if (proposal.actionId.isBlank() || proposal.actionId.length > MAX_ACTION_ID_CHARS) {
            return AssistantToolProposalValidation.Rejected("معرّف الأداة غير صالح.")
        }
        if (proposal.actionId !in registeredActionIds) {
            return AssistantToolProposalValidation.Rejected("الأداة المطلوبة غير مسجلة.")
        }
        if (proposal.arguments.size > ToolRequest.MAX_ARGUMENTS) {
            return AssistantToolProposalValidation.Rejected("عدد معاملات الأداة يتجاوز الحد.")
        }
        if (proposal.arguments.keys.any { key ->
                key.isBlank() || key.length > ToolRequest.MAX_KEY_LENGTH ||
                    normalizeArgumentName(key) in forbiddenArgumentNames
            }
        ) {
            return AssistantToolProposalValidation.Rejected(
                "تحتوي معاملات الأداة على اسم محظور أو غير صالح."
            )
        }
        if (proposal.arguments.values.any { it.length > ToolRequest.MAX_VALUE_LENGTH }) {
            return AssistantToolProposalValidation.Rejected("إحدى قيم معاملات الأداة تتجاوز الحد.")
        }
        if (proposal.explanation.length > MAX_EXPLANATION_CHARS ||
            proposal.explanation.any { it.isISOControl() }
        ) {
            return AssistantToolProposalValidation.Rejected("شرح الأداة غير صالح أو طويل جداً.")
        }
        val request = ToolRequest(proposal.arguments)
        if (!request.isWithinLimits()) {
            return AssistantToolProposalValidation.Rejected("معاملات الأداة غير صالحة.")
        }
        return AssistantToolProposalValidation.Accepted(request, proposal.explanation)
    }

    private fun normalizeArgumentName(key: String): String =
        key.lowercase().filterNot { it == '_' || it == '-' || it.isWhitespace() }

    const val MAX_ACTION_ID_CHARS = 80
    const val MAX_EXPLANATION_CHARS = 300
}

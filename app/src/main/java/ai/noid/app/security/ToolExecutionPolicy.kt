package ai.noid.app

/** Authorization gate for future tool adapters. It never executes actions. */
enum class ToolCapability { READ_SCREEN, READ_FILES, WRITE_FILES, SEND_MESSAGE, MAKE_PURCHASE, DELETE_DATA, CHANGE_DEVICE_SETTINGS, RUN_CODE }
enum class ToolRisk { READ_ONLY, REVERSIBLE, SENSITIVE, DESTRUCTIVE }
enum class ToolDecision { ALLOW, REQUIRE_USER_CONFIRMATION, DENY }

data class ToolAction(val actionId: String, val capability: ToolCapability, val risk: ToolRisk, val description: String)
data class ToolAuthorization(val decision: ToolDecision, val reason: String)

class ToolExecutionPolicy {
    fun evaluate(action: ToolAction, grantedCapabilities: Set<ToolCapability>, userConfirmed: Boolean): ToolAuthorization {
        if (!hasSafeMetadata(action)) {
            return ToolAuthorization(ToolDecision.DENY, "بيانات الإجراء غير مكتملة أو غير صالحة.")
        }
        if (action.capability !in grantedCapabilities) {
            return ToolAuthorization(ToolDecision.DENY, "الصلاحية المطلوبة غير ممنوحة.")
        }
        val confirmationRequired = action.risk == ToolRisk.SENSITIVE || action.risk == ToolRisk.DESTRUCTIVE
        if (confirmationRequired && !userConfirmed) {
            return ToolAuthorization(ToolDecision.REQUIRE_USER_CONFIRMATION, "يتطلب هذا الإجراء موافقة صريحة من المستخدم.")
        }
        return ToolAuthorization(ToolDecision.ALLOW, "الصلاحية متاحة وشروط الموافقة مستوفاة.")
    }

    private fun hasSafeMetadata(action: ToolAction): Boolean {
        if (action.actionId.isBlank() || action.actionId.length > MAX_ACTION_ID_CHARS) return false
        if (action.description.isBlank() || action.description.length > MAX_DESCRIPTION_CHARS) return false
        if (action.actionId.any { it.isISOControl() } || action.description.any { it.isISOControl() }) return false
        return action.actionId.all { it.isLetterOrDigit() || it in "._:-" }
    }

    private companion object {
        const val MAX_ACTION_ID_CHARS = 128
        const val MAX_DESCRIPTION_CHARS = 256
    }
}

package ai.noid.app

/** Authorization gate for future tool adapters. It never executes actions. */
enum class ToolCapability { READ_SCREEN, READ_FILES, WRITE_FILES, SEND_MESSAGE, MAKE_PURCHASE, DELETE_DATA, CHANGE_DEVICE_SETTINGS, RUN_CODE }
enum class ToolRisk { READ_ONLY, REVERSIBLE, SENSITIVE, DESTRUCTIVE }
enum class ToolDecision { ALLOW, REQUIRE_USER_CONFIRMATION, DENY }

data class ToolAction(val actionId: String, val capability: ToolCapability, val risk: ToolRisk, val description: String)
data class ToolAuthorization(val decision: ToolDecision, val reason: String)

class ToolExecutionPolicy {
    fun evaluate(action: ToolAction, grantedCapabilities: Set<ToolCapability>, userConfirmed: Boolean): ToolAuthorization {
        if (action.actionId.isBlank() || action.description.isBlank()) {
            return ToolAuthorization(ToolDecision.DENY, "بيانات الإجراء غير مكتملة.")
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
}

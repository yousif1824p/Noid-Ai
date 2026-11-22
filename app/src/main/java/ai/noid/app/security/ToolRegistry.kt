package ai.noid.app

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface NoidToolAdapter {
    val action: ToolAction
    val modelToolName: String? get() = null
    val modelToolDescriptionJson: String? get() = null
    fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? = null
    fun validate(request: ToolRequest): String?
    fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> = emptySet()
    suspend fun execute(request: ToolRequest): String
}

class ToolRequest(arguments: Map<String, String>) {
    val arguments: Map<String, String> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(arguments))

    fun isWithinLimits(): Boolean =
        arguments.size <= MAX_ARGUMENTS &&
            arguments.all { (key, value) ->
                key.isNotBlank() && key.length <= MAX_KEY_LENGTH &&
                    key.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } &&
                    value.length <= MAX_VALUE_LENGTH
            }

    companion object {
        const val MAX_ARGUMENTS = 16
        const val MAX_KEY_LENGTH = 64
        const val MAX_VALUE_LENGTH = 4096
    }
}

sealed interface ToolRegistryResult {
    data object UnknownTool : ToolRegistryResult
    data class InvalidRequest(val reason: String) : ToolRegistryResult
    data object ApprovalRequired : ToolRegistryResult
    data class Executed(val result: ToolExecutionResult<String>) : ToolRegistryResult
}

/** Schemas are exposed to LiteRT-LM, but all calls must return through manual approval. */
class ToolRegistry(
    private val coordinator: ToolExecutionCoordinator,
    private val auditRepository: ToolAuditRepository,
    private val approvalGate: ToolApprovalGate = ToolApprovalGate()
) {
    private val adapters = linkedMapOf<String, NoidToolAdapter>()
    private val mutablePendingApprovals = MutableStateFlow<List<PendingToolApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingToolApproval>> = mutablePendingApprovals.asStateFlow()

    @Synchronized
    fun register(adapter: NoidToolAdapter) {
        val id = adapter.action.actionId
        require(id.isNotBlank() && id.length <= MAX_ACTION_ID_LENGTH)
        require(id !in adapters) { "A tool with this action ID is already registered." }
        adapter.modelToolName?.let { name ->
            require(name.matches(MODEL_TOOL_NAME_PATTERN)) { "Invalid model tool name." }
            require(adapters.values.none { it.modelToolName == name }) { "Duplicate model tool name." }
            require(!adapter.modelToolDescriptionJson.isNullOrBlank()) {
                "A model-exposed tool must provide a schema."
            }
        }
        adapters[id] = adapter
    }

    /** Tool providers contain schemas only. Accidental automatic execution fails closed. */
    @Synchronized
    fun modelToolProviders(): List<ToolProvider> =
        adapters.values.mapNotNull { adapter ->
            val schema = adapter.modelToolDescriptionJson ?: return@mapNotNull null
            tool(object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String = schema
                override fun execute(paramsJsonString: String): String {
                    throw UnsupportedOperationException(
                        "NOID requires manual tool approval; automatic execution is disabled."
                    )
                }
            })
        }


    /** Read-only catalogue for the planner; schemas never carry execution grants. */
    @Synchronized
    fun planningCatalog(): List<AssistantPlannerToolDescriptor> =
        adapters.values.mapNotNull { adapter ->
            val schema = adapter.modelToolDescriptionJson ?: return@mapNotNull null
            AssistantPlannerToolDescriptor(
                actionId = adapter.action.actionId,
                description = adapter.action.description,
                risk = adapter.action.risk.name,
                schemaJson = schema
            )
        }

    /** Maps untrusted LiteRT-LM arguments into a pending request using app-owned context. */
    fun requestApprovalFromModel(
        modelToolName: String,
        arguments: Map<String, Any?>,
        explanation: String = ""
    ): PendingToolApproval? {
        val adapter = synchronized(this) {
            adapters.values.firstOrNull { it.modelToolName == modelToolName }
        } ?: return null
        if (arguments.size > ToolRequest.MAX_ARGUMENTS || arguments.values.any { it !is String }) {
            return null
        }
        val boundArguments = adapter.bindModelArguments(
            arguments.mapValues { (_, value) -> value as String }
        ) ?: return null
        val proposal = AssistantToolProposal(adapter.action.actionId, boundArguments, explanation)
        val validation = AssistantToolProposalPolicy.validate(proposal, registeredActionIds())
        return when (validation) {
            is AssistantToolProposalValidation.Accepted ->
                requestApproval(adapter.action.actionId, validation.request)
            is AssistantToolProposalValidation.Rejected -> null
        }
    }

    fun requestApproval(proposal: AssistantToolProposal): PendingToolApproval? {
        val validation = AssistantToolProposalPolicy.validate(proposal, registeredActionIds())
        return when (validation) {
            is AssistantToolProposalValidation.Accepted ->
                requestApproval(proposal.actionId, validation.request)
            is AssistantToolProposalValidation.Rejected -> null
        }
    }

    fun requestApproval(actionId: String, request: ToolRequest): PendingToolApproval? {
        val adapter = synchronized(this) { adapters[actionId] } ?: return null
        val risk = adapter.action.risk
        if (risk != ToolRisk.SENSITIVE && risk != ToolRisk.DESTRUCTIVE) return null
        if (!request.isWithinLimits() || adapter.validate(request) != null) return null
        val pending = try {
            approvalGate.createPending(adapter.action, request)
        } catch (_: IllegalStateException) {
            return null
        }
        refreshPendingApprovals()
        return pending
    }


    /**
     * Dispatches one validated plan step. Safe steps use the normal registry
     * gateway; sensitive/destructive steps only create a one-time approval.
     */
    suspend fun executePlannedStep(proposal: AssistantToolProposal): PlannedToolStepDispatch {
        val validation = AssistantToolProposalPolicy.validate(proposal, registeredActionIds())
        if (validation is AssistantToolProposalValidation.Rejected) {
            return PlannedToolStepDispatch.Finished(ToolRegistryResult.InvalidRequest(validation.reason))
        }
        validation as AssistantToolProposalValidation.Accepted
        val adapter = synchronized(this) { adapters[proposal.actionId] }
            ?: return PlannedToolStepDispatch.Finished(ToolRegistryResult.UnknownTool)
        val sensitive = adapter.action.risk == ToolRisk.SENSITIVE ||
            adapter.action.risk == ToolRisk.DESTRUCTIVE
        if (sensitive) {
            val pending = requestApproval(proposal.actionId, validation.request)
                ?: return PlannedToolStepDispatch.Finished(
                    ToolRegistryResult.InvalidRequest("تعذر إنشاء طلب موافقة صالح للخطوة.")
                )
            return PlannedToolStepDispatch.AwaitingApproval(pending)
        }
        return PlannedToolStepDispatch.Finished(invoke(proposal.actionId, validation.request))
    }

    fun approveFromUser(pendingId: String): Boolean {
        val approved = approvalGate.approve(pendingId)
        refreshPendingApprovals()
        return approved
    }

    suspend fun approveAndInvoke(pendingId: String): ToolRegistryResult {
        val approved = approvalGate.approveAndGet(pendingId) ?: run {
            refreshPendingApprovals()
            return ToolRegistryResult.ApprovalRequired
        }
        refreshPendingApprovals()
        val adapter = synchronized(this) { adapters[approved.actionId] }
            ?: return ToolRegistryResult.UnknownTool
        val request = ToolRequest(approved.arguments)
        return invoke(approved.actionId, request, approved.id)
    }

    fun cancelApproval(pendingId: String): Boolean {
        val cancelled = approvalGate.cancel(pendingId)
        refreshPendingApprovals()
        return cancelled
    }

    private fun refreshPendingApprovals() {
        mutablePendingApprovals.value = approvalGate.pendingApprovals()
    }

    /**
     * Platform capabilities are always re-derived by the registered adapter at
     * execution time. Callers cannot supply or retain a stale/fabricated grant.
     */
    suspend fun invoke(
        actionId: String,
        request: ToolRequest,
        approvalId: String? = null
    ): ToolRegistryResult {
        val adapter = synchronized(this) { adapters[actionId] }
            ?: return ToolRegistryResult.UnknownTool
        if (!request.isWithinLimits()) {
            return ToolRegistryResult.InvalidRequest("حجم أو عدد معاملات الأداة يتجاوز الحدود.")
        }
        val validationError = adapter.validate(request)
        if (validationError != null) {
            return ToolRegistryResult.InvalidRequest(validationError.take(MAX_VALIDATION_MESSAGE_LENGTH))
        }
        val sensitive = adapter.action.risk == ToolRisk.SENSITIVE ||
            adapter.action.risk == ToolRisk.DESTRUCTIVE
        if (sensitive && (approvalId == null ||
                !approvalGate.consume(approvalId, adapter.action, request))
        ) return ToolRegistryResult.ApprovalRequired

        val execution = coordinator.execute(
            action = adapter.action,
            grantedCapabilities = resolveCapabilities { adapter.grantedCapabilities(request) },
            userConfirmed = sensitive,
            auditRepository = auditRepository
        ) { adapter.execute(request) }
        return ToolRegistryResult.Executed(execution)
    }

    /**
     * Entry point for user-started UI actions. Screen workflows and model-requested
     * adapters share the same policy and audit boundary through this registry.
     */
    suspend fun <T> executeUserAction(
        action: ToolAction,
        grantedCapabilities: () -> Set<ToolCapability>,
        userConfirmed: Boolean,
        operation: suspend () -> T
    ): ToolExecutionResult<T> {
        // Re-read the exact platform grant immediately before policy evaluation.
        // A revoked or failing permission query must never reuse an earlier grant.
        val currentGrants = resolveCapabilities(grantedCapabilities)
        return coordinator.execute(
        action = action,
        grantedCapabilities = currentGrants,
        userConfirmed = userConfirmed,
        auditRepository = auditRepository,
        operation = operation
        )
    }

    private fun resolveCapabilities(provider: () -> Set<ToolCapability>): Set<ToolCapability> =
        try {
            provider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Permission lookup failures deny access; cancellation remains observable.
            emptySet()
        }

    @Synchronized
    fun registeredActionIds(): Set<String> = adapters.keys.toSet()

    private companion object {
        const val MAX_ACTION_ID_LENGTH = 80
        const val MAX_VALIDATION_MESSAGE_LENGTH = 200
        val MODEL_TOOL_NAME_PATTERN = Regex("[a-z][a-z0-9_]{1,63}")
    }
}

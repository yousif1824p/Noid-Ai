package ai.noid.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Untrusted sequence of tool intents; contains no grants, tickets, or executable code. */
data class AssistantTaskPlan(val planId: String, val goal: String, val steps: List<AssistantTaskPlanStep>)
data class AssistantTaskPlanStep(val stepId: String, val proposal: AssistantToolProposal)

sealed interface PlannedToolStepDispatch {
    data class AwaitingApproval(val pending: PendingToolApproval) : PlannedToolStepDispatch
    data class Finished(val result: ToolRegistryResult) : PlannedToolStepDispatch
}

sealed interface AssistantTaskPlanProgress {
    data class Rejected(val reason: String) : AssistantTaskPlanProgress
    data class AwaitingApproval(
        val planId: String,
        val stepIndex: Int,
        val totalSteps: Int,
        val pending: PendingToolApproval
    ) : AssistantTaskPlanProgress
    data class Completed(val planId: String, val results: List<ToolRegistryResult>) : AssistantTaskPlanProgress
    data class Stopped(
        val planId: String,
        val stepId: String,
        val completedSteps: Int,
        val reason: String
    ) : AssistantTaskPlanProgress
}

/** Validates the complete plan before any step is allowed to execute. */
object AssistantTaskPlanPolicy {
    const val MAX_STEPS = 8
    const val MAX_PLAN_ID_CHARS = 64
    const val MAX_GOAL_CHARS = 500
    const val MAX_STEP_ID_CHARS = 64

    fun validate(plan: AssistantTaskPlan, registeredActionIds: Set<String>): String? {
        if (!plan.planId.matches(Regex("[A-Za-z0-9._:-]{1,$MAX_PLAN_ID_CHARS}"))) {
            return "معرّف الخطة غير صالح."
        }
        if (plan.goal.isBlank() || plan.goal.length > MAX_GOAL_CHARS ||
            plan.goal.any { it.isISOControl() }
        ) return "هدف الخطة فارغ أو غير صالح أو طويل جداً."
        if (plan.steps.isEmpty() || plan.steps.size > MAX_STEPS) {
            return "عدد خطوات الخطة خارج الحدود المسموحة."
        }
        val ids = plan.steps.map { it.stepId }
        if (ids.any {
                it.isBlank() || it.length > MAX_STEP_ID_CHARS ||
                    !it.matches(Regex("[A-Za-z0-9._:-]+"))
            } || ids.toSet().size != ids.size
        ) return "معرّفات خطوات الخطة غير صالحة أو مكررة."

        for (step in plan.steps) {
            when (val validation = AssistantToolProposalPolicy.validate(step.proposal, registeredActionIds)) {
                is AssistantToolProposalValidation.Accepted -> Unit
                is AssistantToolProposalValidation.Rejected ->
                    return "الخطوة ${step.stepId} غير صالحة: ${validation.reason}"
            }
        }
        return null
    }
}

/**
 * Runs plans sequentially through ToolRegistry. Sensitive/destructive steps
 * pause at the existing one-time approval gate. Any failed step stops the plan.
 */
class AssistantTaskPlanCoordinator(private val toolRegistry: ToolRegistry) {
    private data class Session(
        val plan: AssistantTaskPlan,
        val mutex: Mutex = Mutex(),
        val results: MutableList<ToolRegistryResult> = mutableListOf(),
        var nextStepIndex: Int = 0,
        var waitingApprovalId: String? = null
    )

    private val sessions = mutableMapOf<String, Session>()

    suspend fun start(plan: AssistantTaskPlan): AssistantTaskPlanProgress {
        val validation = AssistantTaskPlanPolicy.validate(plan, toolRegistry.registeredActionIds())
        if (validation != null) return AssistantTaskPlanProgress.Rejected(validation)

        val session = Session(plan)
        synchronized(sessions) {
            if (sessions.containsKey(plan.planId)) {
                return AssistantTaskPlanProgress.Rejected("توجد خطة نشطة بهذا المعرّف.")
            }
            sessions[plan.planId] = session
        }
        return try {
            session.mutex.withLock { advanceLocked(session) }
        } catch (cancelled: CancellationException) {
            removeSession(session)
            throw cancelled
        } catch (_: Exception) {
            removeSession(session)
            AssistantTaskPlanProgress.Stopped(
                plan.planId,
                plan.steps.getOrNull(session.nextStepIndex)?.stepId ?: "unknown",
                session.results.size,
                "تعذر متابعة تنفيذ الخطة بسبب خطأ داخلي."
            )
        }
    }

    /** Call only after the user chooses Approve in the existing approval UI. */
    suspend fun approve(planId: String, pendingApprovalId: String): AssistantTaskPlanProgress {
        val session = synchronized(sessions) { sessions[planId] }
            ?: return AssistantTaskPlanProgress.Rejected("الخطة غير موجودة أو انتهت.")
        return try {
            session.mutex.withLock {
                if (session.waitingApprovalId != pendingApprovalId) {
                    return@withLock AssistantTaskPlanProgress.Rejected("طلب الموافقة لا يطابق الخطوة الحالية.")
                }
                val result = toolRegistry.approveAndInvoke(pendingApprovalId)
                session.waitingApprovalId = null
                val failure = terminalFailure(result)
                session.results += result
                if (failure != null) stopLocked(session, failure)
                else {
                    session.nextStepIndex++
                    advanceLocked(session)
                }
            }
        } catch (cancelled: CancellationException) {
            removeSession(session)
            throw cancelled
        } catch (_: Exception) {
            removeSession(session)
            AssistantTaskPlanProgress.Stopped(
                planId,
                session.plan.steps.getOrNull(session.nextStepIndex)?.stepId ?: "unknown",
                session.results.size,
                "تعذر إكمال الخطوة بعد الموافقة."
            )
        }
    }

    /** Rejection cancels the pending ticket and prevents every later step. */
    suspend fun reject(planId: String): AssistantTaskPlanProgress {
        val session = synchronized(sessions) { sessions[planId] }
            ?: return AssistantTaskPlanProgress.Rejected("الخطة غير موجودة أو انتهت.")
        return session.mutex.withLock {
            session.waitingApprovalId?.let(toolRegistry::cancelApproval)
            session.waitingApprovalId = null
            val stepId = session.plan.steps.getOrNull(session.nextStepIndex)?.stepId ?: "unknown"
            removeSession(session)
            AssistantTaskPlanProgress.Stopped(
                planId, stepId, session.results.size, "رفض المستخدم متابعة الخطة."
            )
        }
    }

    private suspend fun advanceLocked(session: Session): AssistantTaskPlanProgress {
        while (session.nextStepIndex < session.plan.steps.size) {
            val step = session.plan.steps[session.nextStepIndex]
            when (val dispatch = toolRegistry.executePlannedStep(step.proposal)) {
                is PlannedToolStepDispatch.AwaitingApproval -> {
                    session.waitingApprovalId = dispatch.pending.id
                    return AssistantTaskPlanProgress.AwaitingApproval(
                        session.plan.planId, session.nextStepIndex, session.plan.steps.size, dispatch.pending
                    )
                }
                is PlannedToolStepDispatch.Finished -> {
                    val failure = terminalFailure(dispatch.result)
                    session.results += dispatch.result
                    if (failure != null) return stopLocked(session, failure)
                    session.nextStepIndex++
                }
            }
        }
        removeSession(session)
        return AssistantTaskPlanProgress.Completed(session.plan.planId, session.results.toList())
    }

    private fun terminalFailure(result: ToolRegistryResult): String? = when (result) {
        ToolRegistryResult.UnknownTool -> "الأداة المطلوبة لم تعد مسجلة."
        is ToolRegistryResult.InvalidRequest -> "رفضت الأداة معاملات الخطوة."
        ToolRegistryResult.ApprovalRequired -> "لم تكتمل الموافقة المطلوبة."
        is ToolRegistryResult.Executed -> when (val execution = result.result) {
            is ToolExecutionResult.Completed -> if (execution.completionAudited) null
                else "اكتمل الإجراء لكن تعذر تسجيل حدث التدقيق النهائي؛ أوقفت الخطة."
            is ToolExecutionResult.Rejected -> "رفضت سياسة الصلاحيات تنفيذ الخطوة."
            is ToolExecutionResult.AuditUnavailable -> "سجل التدقيق غير متاح؛ أوقفت الخطة."
            is ToolExecutionResult.Failed -> "فشلت الأداة؛ أوقفت الخطة."
        }
    }

    private fun stopLocked(session: Session, reason: String): AssistantTaskPlanProgress.Stopped {
        val stepId = session.plan.steps.getOrNull(session.nextStepIndex)?.stepId ?: "unknown"
        removeSession(session)
        return AssistantTaskPlanProgress.Stopped(session.plan.planId, stepId, session.results.size, reason)
    }

    private fun removeSession(session: Session) {
        synchronized(sessions) {
            if (sessions[session.plan.planId] === session) sessions.remove(session.plan.planId)
        }
    }
}

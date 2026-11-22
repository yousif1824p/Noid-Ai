package ai.noid.app

/**
 * Converts only fully audited successful plan metadata into a pending lesson.
 * Tool arguments, file contents, and raw tool outputs are intentionally excluded.
 */
data class TaskPlanExperienceDraft(
    val title: String,
    val content: String,
    val source: String,
    val deduplicationKey: String
)

object TaskPlanExperienceDraftFactory {
    private val actionIdPattern = Regex("[A-Za-z0-9._-]{1,80}")

    fun from(progress: AssistantTaskPlanProgress.Completed): TaskPlanExperienceDraft? {
        if (progress.planId.isBlank() || progress.planId.length > 64) return null
        if (progress.actionIds.isEmpty() ||
            progress.actionIds.size > AssistantTaskPlanPolicy.MAX_STEPS ||
            progress.results.size != progress.actionIds.size
        ) return null
        if (progress.actionIds.any { !actionIdPattern.matches(it) }) return null

        val allAudited = progress.results.all { result ->
            result is ToolRegistryResult.Executed &&
                result.result is ToolExecutionResult.Completed<*> &&
                result.result.completionAudited
        }
        if (!allAudited) return null

        val sequence = progress.actionIds.joinToString(" ← ")
        val title = "خبرة تنفيذ: ${sequence}".take(LearningRepository.MAX_TITLE_CHARS)
        val content = buildString {
            append("اكتملت خطة محلية متعددة الخطوات بنجاح بعد تسجيل اكتمال جميع إجراءاتها في سجل التدقيق. ")
            append("عدد الخطوات: ")
            append(progress.actionIds.size)
            append(". تسلسل الأدوات: ")
            append(sequence)
            append(". هذه الخبرة تحفظ نمط الأدوات فقط؛ ولا تتضمن معاملات التنفيذ أو محتوى الملفات أو مخرجات الأدوات.")
        }.take(LearningRepository.MAX_CONTENT_CHARS)
        return TaskPlanExperienceDraft(
            title = title,
            content = content,
            source = "خبرة تنفيذ خطة محلية مكتملة ومدققة",
            deduplicationKey = "task-plan:${progress.planId}"
        )
    }
}

package ai.noid.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.flow.collect

/** A model-visible tool is descriptive only; it grants no authority. */
data class AssistantPlannerToolDescriptor(
    val actionId: String,
    val description: String,
    val risk: String,
    val schemaJson: String
)

/**
 * Converts an explicit user task into a bounded, untrusted plan using the
 * currently configured assistant engine. No tool is exposed to this inference
 * turn and no model-produced action is executed here.
 */
class AssistantTaskPlanner(
    private val engine: AssistantEngine,
    private val toolRegistry: ToolRegistry
) {
    suspend fun createPlan(request: AssistantTurnRequest, task: String): AssistantTaskPlan {
        val normalizedTask = task.trim()
        require(normalizedTask.isNotEmpty() && normalizedTask.length <= MAX_TASK_CHARS) {
            "طلب التخطيط فارغ أو يتجاوز الحد المسموح."
        }
        check(engine.status.value is AssistantEngineStatus.Ready) {
            "محرك الذكاء الاصطناعي المحلي غير جاهز."
        }
        val catalog = toolRegistry.planningCatalog()
        require(catalog.isNotEmpty()) { "لا توجد أدوات مسجلة متاحة للتخطيط حالياً." }

        val prompt = buildPrompt(normalizedTask, catalog)
        val lastUserIndex = request.conversation.indexOfLast { it.fromUser }
        require(lastUserIndex >= 0) { "رسالة المستخدم غير موجودة." }
        val planningConversation = request.conversation.mapIndexed { index, message ->
            if (index == lastUserIndex) message.copy(text = prompt) else message
        }
        val planningRequest = request.copy(
            conversation = planningConversation,
            toolProviders = emptyList()
        )

        val output = StringBuilder()
        var completed = false
        engine.generate(planningRequest).collect { event ->
            when (event) {
                is AssistantStreamEvent.TextDelta -> {
                    check(!completed) { "أرسل النموذج نصاً بعد اكتمال التخطيط." }
                    require(event.text.length <= MAX_PLAN_OUTPUT_CHARS - output.length) {
                        "تجاوز خرج التخطيط الحد المسموح."
                    }
                    output.append(event.text)
                }
                is AssistantStreamEvent.ToolCall ->
                    throw IllegalStateException("طلب النموذج استدعاء أداة أثناء إنشاء الخطة؛ رُفض الطلب.")
                AssistantStreamEvent.Completed -> {
                    check(!completed) { "أرسل النموذج أكثر من إشارة اكتمال." }
                    completed = true
                }
            }
        }
        check(completed && output.isNotEmpty()) { "لم يُكمل النموذج إنشاء الخطة." }

        val plan = AssistantTaskPlanJsonParser.parse(
            output.toString(),
            planId = UUID.randomUUID().toString()
        )
        val validation = AssistantTaskPlanPolicy.validate(plan, catalog.map { it.actionId }.toSet())
        require(validation == null) { validation ?: "الخطة غير صالحة." }
        return plan
    }

    private fun buildPrompt(task: String, catalog: List<AssistantPlannerToolDescriptor>): String =
        buildString {
            append("أنت مخطط مهام NOID. حوّل طلب المستخدم إلى خطة أدوات محدودة. ")
            append("أخرج JSON فقط دون Markdown أو شرح خارجه. لا تنفذ الأدوات ولا تدّع تنفيذها. ")
            append("لا تستخدم إلا actionId موجوداً حرفياً في قائمة الأدوات أدناه. ")
            append("كل arguments يجب أن تكون كائن JSON قيمه نصوص فقط، وبحسب مخطط الأداة. ")
            append("لا تخترع معرفات اختيار مشروع أو أذونات أو URI أو صلاحيات أو شيفرة. ")
            append("لا تقترح أدوات غير موجودة. إذا لم تكفِ المعلومات أو تعذر بناء خطة آمنة، ")
            append("أخرج خطوة واحدة فقط من أداة قراءة مناسبة أو ارفض بصيغة JSON غير صالحة؛ لا تخمّن. ")
            append("الحد الأقصى 8 خطوات. الصيغة الدقيقة: ")
            append("{\"goal\":\"...\",\"steps\":[{\"actionId\":\"...\",\"arguments\":{\"key\":\"value\"},\"explanation\":\"...\"}]}. ")
            append("مخططات الأدوات المسجلة:\n")
            catalog.forEach { descriptor ->
                append("ACTION_ID=")
                append(descriptor.actionId)
                append(" | RISK=")
                append(descriptor.risk)
                append(" | DESCRIPTION=")
                append(descriptor.description)
                append(" | SCHEMA=")
                append(descriptor.schemaJson)
                append("\n")
            }
            append("طلب المستخدم:\n")
            append(task)
        }

    companion object {
        const val MAX_TASK_CHARS = 2_000
        const val MAX_PLAN_OUTPUT_CHARS = 16_000
    }
}

/** Strict parser for the model's untrusted JSON plan envelope. */
object AssistantTaskPlanJsonParser {
    fun parse(raw: String, planId: String): AssistantTaskPlan {
        require(raw.length in 2..AssistantTaskPlanner.MAX_PLAN_OUTPUT_CHARS) {
            "خرج الخطة فارغ أو طويل جداً."
        }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "خرج التخطيط ليس كائن JSON صالحاً." }
        val root = JSONObject(raw.substring(start, end + 1))
        require(root.keys().asSequence().toSet() == setOf("goal", "steps")) {
            "حقول الخطة غير مطابقة للمخطط."
        }
        val goal = root.get("goal") as? String
            ?: throw IllegalArgumentException("هدف الخطة يجب أن يكون نصاً.")
        val stepsJson = root.get("steps") as? JSONArray
            ?: throw IllegalArgumentException("خطوات الخطة يجب أن تكون مصفوفة.")
        require(stepsJson.length() in 1..AssistantTaskPlanPolicy.MAX_STEPS) {
            "عدد خطوات الخطة خارج الحدود."
        }

        val steps = (0 until stepsJson.length()).map { index ->
            val item = stepsJson.get(index) as? JSONObject
                ?: throw IllegalArgumentException("إحدى خطوات الخطة ليست كائناً.")
            require(item.keys().asSequence().toSet() == setOf("actionId", "arguments", "explanation")) {
                "حقول إحدى الخطوات غير مطابقة للمخطط."
            }
            val actionId = item.get("actionId") as? String
                ?: throw IllegalArgumentException("معرّف الأداة يجب أن يكون نصاً.")
            val explanation = item.get("explanation") as? String
                ?: throw IllegalArgumentException("شرح الخطوة يجب أن يكون نصاً.")
            val argumentsJson = item.get("arguments") as? JSONObject
                ?: throw IllegalArgumentException("معاملات الأداة يجب أن تكون كائناً.")
            val arguments = linkedMapOf<String, String>()
            val keys = argumentsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = argumentsJson.get(key) as? String
                    ?: throw IllegalArgumentException("يجب أن تكون جميع معاملات الأداة نصوصاً.")
                arguments[key] = value
            }
            AssistantTaskPlanStep(
                stepId = "step-" + (index + 1),
                proposal = AssistantToolProposal(actionId, arguments, explanation)
            )
        }
        return AssistantTaskPlan(planId, goal, steps)
    }
}

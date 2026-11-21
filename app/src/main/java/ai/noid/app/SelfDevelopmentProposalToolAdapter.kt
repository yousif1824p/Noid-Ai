package ai.noid.app

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads one selected SAF text file and stores a proposal; never writes project files. */
class SelfDevelopmentProposalToolAdapter(
    context: Context,
    private val selectedTree: SelectedProjectTreeSession,
    private val files: ProjectFileRepository,
    private val proposals: SelfDevelopmentProposalRepository
) : NoidToolAdapter {
    private val resolver = context.applicationContext.contentResolver

    override val action = ToolAction(
        actionId = ACTION_ID,
        capability = ToolCapability.READ_FILES,
        risk = ToolRisk.SENSITIVE,
        description = "قراءة ملف من المشروع المختار وحفظ مقترح تطوير للمراجعة دون تطبيقه"
    )
    override val modelToolName = MODEL_TOOL_NAME
    override val modelToolDescriptionJson = """
        {
          "name": "$MODEL_TOOL_NAME",
          "description": "أنشئ مقترح تطوير لملف نصي موجود في المشروع المختار. لا تعدل الملف. أرسل المحتوى الجديد كاملاً بحد أقصى 3000 محرف، مع عنوان وسبب ومسار نسبي.",
          "parameters": {
            "type": "object",
            "properties": {
              "title": { "type": "string" },
              "rationale": { "type": "string" },
              "path": { "type": "string" },
              "proposedContent": { "type": "string" }
            },
            "required": ["title", "rationale", "path", "proposedContent"],
            "additionalProperties": false
          }
        }
    """.trimIndent()

    override fun bindModelArguments(arguments: Map<String, Any?>): Map<String, String>? {
        if (arguments.keys != MODEL_ARGUMENTS || arguments.values.any { it !is String }) return null
        val selectionId = selectedTree.current()?.selectionId ?: return null
        return arguments.mapValues { it.value as String } + (ARG_SELECTION_ID to selectionId)
    }

    override fun validate(request: ToolRequest): String? =
        SelfDevelopmentProposalToolPolicy.validate(request.arguments, selectedTree.current()?.selectionId)

    override fun grantedCapabilities(request: ToolRequest): Set<ToolCapability> {
        val selection = selectedTree.current() ?: return emptySet()
        if (request.arguments[ARG_SELECTION_ID] != selection.selectionId) return emptySet()
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == selection.uri }
            ?: return emptySet()
        return if (grant.isReadPermission) setOf(ToolCapability.READ_FILES) else emptySet()
    }

    override suspend fun execute(request: ToolRequest): String {
        val selection = selectedTree.current() ?: throw IOException("لم يتم اختيار مجلد مشروع.")
        if (request.arguments[ARG_SELECTION_ID] != selection.selectionId) {
            throw IOException("تغير مجلد المشروع بعد إنشاء الطلب؛ أعد المحاولة.")
        }
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == selection.uri }
        if (grant?.isReadPermission != true) throw SecurityException("إذن قراءة مجلد المشروع غير متاح.")
        val path = request.arguments[ARG_PATH] ?: throw IOException("مسار الملف مفقود.")
        val original = withContext(Dispatchers.IO) { files.readText(selection.uri, path) }
        val proposalId = proposals.propose(
            title = request.arguments[ARG_TITLE] ?: throw IOException("عنوان المقترح مفقود."),
            rationale = request.arguments[ARG_RATIONALE] ?: throw IOException("سبب المقترح مفقود."),
            relativePath = path,
            originalContent = original,
            proposedContent = request.arguments[ARG_PROPOSED_CONTENT]
                ?: throw IOException("المحتوى المقترح مفقود.")
        )
        return "حُفظ مقترح التطوير رقم $proposalId للملف $path. لم يُعدّل الملف الأصلي؛ راجع المقترح في شاشة مراجعة تطوير NOID."
    }

    companion object {
        const val ACTION_ID = "self-development-create-proposal"
        const val MODEL_TOOL_NAME = "propose_project_file_change"
        const val ARG_SELECTION_ID = "selectionId"
        const val ARG_TITLE = "title"
        const val ARG_RATIONALE = "rationale"
        const val ARG_PATH = "path"
        const val ARG_PROPOSED_CONTENT = "proposedContent"
        const val MAX_PROPOSED_CHARS = 3_000
        private val MODEL_ARGUMENTS = setOf(ARG_TITLE, ARG_RATIONALE, ARG_PATH, ARG_PROPOSED_CONTENT)
    }
}

object SelfDevelopmentProposalToolPolicy {
    fun validate(arguments: Map<String, String>, activeSelectionId: String?): String? {
        if (arguments.keys != setOf(
                SelfDevelopmentProposalToolAdapter.ARG_SELECTION_ID,
                SelfDevelopmentProposalToolAdapter.ARG_TITLE,
                SelfDevelopmentProposalToolAdapter.ARG_RATIONALE,
                SelfDevelopmentProposalToolAdapter.ARG_PATH,
                SelfDevelopmentProposalToolAdapter.ARG_PROPOSED_CONTENT
            )
        ) return "معاملات مقترح التطوير غير معروفة أو غير مكتملة."
        if (activeSelectionId.isNullOrBlank()) return "اختر مجلد المشروع أولاً."
        if (arguments[SelfDevelopmentProposalToolAdapter.ARG_SELECTION_ID] != activeSelectionId) {
            return "طلب المقترح لا يطابق اختيار المشروع الحالي."
        }
        val path = arguments[SelfDevelopmentProposalToolAdapter.ARG_PATH] ?: return "مسار الملف مفقود."
        val normalized = ProjectFilePolicy.normalizeRelativePath(path) ?: return "مسار الملف غير صالح."
        if (normalized != path) return "يجب أن يكون المسار نسبياً ومنسقاً."
        if (!ProjectFilePolicy.isEditableTextFile(normalized.substringAfterLast('/'))) {
            return "نوع الملف غير مدعوم لمقترحات التطوير."
        }
        val title = arguments[SelfDevelopmentProposalToolAdapter.ARG_TITLE] ?: return "عنوان المقترح مفقود."
        if (title.isBlank() || title.length > SelfDevelopmentProposalRepository.MAX_TITLE_CHARS) {
            return "عنوان المقترح فارغ أو طويل جداً."
        }
        val rationale = arguments[SelfDevelopmentProposalToolAdapter.ARG_RATIONALE] ?: return "سبب المقترح مفقود."
        if (rationale.isBlank() || rationale.length > SelfDevelopmentProposalRepository.MAX_RATIONALE_CHARS) {
            return "سبب المقترح فارغ أو طويل جداً."
        }
        val content = arguments[SelfDevelopmentProposalToolAdapter.ARG_PROPOSED_CONTENT]
            ?: return "المحتوى المقترح مفقود."
        if (content.isEmpty() || content.length > SelfDevelopmentProposalToolAdapter.MAX_PROPOSED_CHARS ||
            content.any {
                (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') ||
                    Character.getType(it) == Character.FORMAT.toInt()
            }
        ) return "المحتوى المقترح فارغ أو يتجاوز الحد أو يحتوي محارف تحكم."
        return null
    }
}

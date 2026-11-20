package ai.noid.app

/** Produces a consent-safe preview without exposing argument values or file contents. */
object ToolApprovalArgumentFormatter {
    fun summarize(arguments: Map<String, String>): String =
        summarize(actionId = null, arguments = arguments)

    /**
     * Shows exact relative paths for project file operations. Selection tokens stay
     * hidden; bounded file-edit content is shown in full for informed approval.
     */
    fun summarize(actionId: String?, arguments: Map<String, String>): String {
        if (actionId == ProjectFileEditToolAdapter.ACTION_ID) {
            val path = arguments[ProjectFileEditToolAdapter.ARG_PATH]
            val content = arguments[ProjectFileEditToolAdapter.ARG_CONTENT]
            if (path != null && content != null) {
                val visibleContent = content
                    .replace("\r", "")
                    .replace("\n", "↵\n")
                    .replace("\t", "⇥")
                return buildString {
                    append("الملف الذي سيُستبدل محتواه: ")
                    append(safeVisible(path, MAX_VISIBLE_PATH_CHARS))
                    if (path.length > MAX_VISIBLE_PATH_CHARS) append("…")
                    append("\nالمحتوى الكامل المقترح (")
                    append(content.length)
                    append(" حرفاً):\n")
                    append(visibleContent)
                    append("\nستُحفظ نسخة احتياطية من المحتوى السابق قبل الكتابة. معرّف اختيار المجلد مخفي.")
                }
            }
        }

        if (actionId == ProjectFileRenameToolAdapter.ACTION_ID) {
            val path = arguments[ProjectFileRenameToolAdapter.ARG_PATH]
            val newName = arguments[ProjectFileRenameToolAdapter.ARG_NEW_NAME]
            if (path != null && newName != null) {
                return buildString {
                    append("الملف الحالي: ")
                    append(safeVisible(path, MAX_VISIBLE_PATH_CHARS))
                    if (path.length > MAX_VISIBLE_PATH_CHARS) append("…")
                    append("\nالاسم الجديد: ")
                    append(safeVisible(newName, MAX_VISIBLE_NAME_CHARS))
                    if (newName.length > MAX_VISIBLE_NAME_CHARS) append("…")
                    append("\nمعرّف اختيار المجلد مخفي. ستتغير تسمية الملف فقط؛ لن يُنقل محتواه أو يُستبدل.")
                }
            }
        }

        if (actionId == ProjectFileBackupRestoreToolAdapter.ACTION_ID) {
            val path = arguments[ProjectFileBackupRestoreToolAdapter.ARG_PATH]
            val backupId = arguments[ProjectFileBackupRestoreToolAdapter.ARG_BACKUP_ID]
            if (path != null && backupId != null) {
                return buildString {
                    append("الملف المستهدف: ")
                    append(safeVisible(path, MAX_VISIBLE_PATH_CHARS))
                    if (path.length > MAX_VISIBLE_PATH_CHARS) append("…")
                    append("\nالنسخة المطلوبة: ")
                    append(safeVisible(backupId, MAX_VISIBLE_NAME_CHARS))
                    append("\nسيُستبدل محتوى الملف بهذه النسخة. سينشئ NOID نسخة من المحتوى الحالي للرجوع عنه.")
                }
            }
        }

        val pathAction = actionId == ProjectFileReadToolAdapter.ACTION_ID ||
            actionId == ProjectFileCreateToolAdapter.ACTION_ID ||
            actionId == ProjectFileBackupListToolAdapter.ACTION_ID
        if (pathAction) {
            val path = arguments[ProjectFileReadToolAdapter.ARG_PATH]
            if (path != null) {
                val safePath = safeVisible(path, MAX_VISIBLE_PATH_CHARS)
                val truncated = path.length > MAX_VISIBLE_PATH_CHARS
                return buildString {
                    append(
                        if (actionId == ProjectFileCreateToolAdapter.ACTION_ID)
                            "الملف النصي الجديد: "
                        else if (actionId == ProjectFileBackupListToolAdapter.ACTION_ID)
                            "ملف النسخ الاحتياطية: "
                        else "الملف الذي سيُقرأ: "
                    )
                    append(safePath)
                    if (truncated) append("…")
                    append("\nمعرّف اختيار المجلد مخفي.")
                    when (actionId) {
                        ProjectFileReadToolAdapter.ACTION_ID ->
                            append(" لن يُعرض محتوى الملف قبل الموافقة.")
                        ProjectFileCreateToolAdapter.ACTION_ID ->
                            append(" سيُنشأ الملف فارغاً ولن يُستبدل ملف موجود.")
                        ProjectFileBackupListToolAdapter.ACTION_ID ->
                            append(" سيُعرض سجل النسخ الاحتياطية دون محتواها.")
                    }
                }
            }
        }

        if (arguments.isEmpty()) return "لا توجد معاملات إضافية."
        val visible = arguments.entries.take(MAX_VISIBLE_ARGUMENTS).mapIndexed { index, entry ->
            "${index + 1}. ${entry.key.take(MAX_KEY_LENGTH)} — قيمة مخفية (${entry.value.length} حرف)"
        }
        val remaining = arguments.size - visible.size
        return buildString {
            append(visible.joinToString("\n"))
            if (remaining > 0) append("\nومعاملات أخرى: $remaining")
        }
    }

    private fun safeVisible(value: String, maxChars: Int): String =
        value.filterNot {
            it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
        }.take(maxChars)

    private const val MAX_VISIBLE_ARGUMENTS = 8
    private const val MAX_KEY_LENGTH = 64
    private const val MAX_VISIBLE_PATH_CHARS = 240
    private const val MAX_VISIBLE_NAME_CHARS = 128
}

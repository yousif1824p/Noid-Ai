package ai.noid.app

/**
 * Produces a conservative, in-memory remediation preview for a tiny set of
 * unambiguous AndroidManifest XML attributes. It never writes to SAF or disk.
 * Unsupported findings remain manual-review items.
 */
data class ProjectRemediationPreview(
    val supported: Boolean,
    val relativePath: String,
    val originalContent: String,
    val proposedContent: String?,
    val diff: ProjectDiffResult?,
    val remainingMatchingFindings: Int?,
    val reason: String
) {
    val resolvesFindingInPreview: Boolean
        get() = supported && remainingMatchingFindings == 0
}

object ProjectRemediationPreviewer {
    private data class Rule(
        val idPrefix: String,
        val attributePattern: Regex,
        val explanation: String
    )

    private val rules = listOf(
        Rule(
            idPrefix = "source.debuggable:",
            attributePattern = Regex("""(?i)(android:debuggable\s*=\s*)(["'])true\2"""),
            explanation = "معاينة تعطيل android:debuggable في هذا السطر فقط."
        ),
        Rule(
            idPrefix = "source.cleartext_traffic:",
            attributePattern = Regex("""(?i)(android:usesCleartextTraffic\s*=\s*)(["'])true\2"""),
            explanation = "معاينة تعطيل usesCleartextTraffic في هذا السطر فقط. راجع استثناءات Network Security Config يدوياً."
        )
    )

    fun preview(
        file: ProjectSourceFile,
        finding: ApkSecurityFinding
    ): ProjectRemediationPreview {
        fun unsupported(reason: String) = ProjectRemediationPreview(
            supported = false,
            relativePath = file.relativePath,
            originalContent = file.content,
            proposedContent = null,
            diff = null,
            remainingMatchingFindings = null,
            reason = reason
        )

        val path = ProjectFilePolicy.normalizeRelativePath(file.relativePath)
            ?: return unsupported("مسار الملف غير صالح.")
        if (path != file.relativePath || !path.endsWith(".xml", ignoreCase = true)) {
            return unsupported("المعاينة الآلية المحدودة متاحة لملفات XML فقط.")
        }
        if (file.content.toByteArray(Charsets.UTF_8).size > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) {
            return unsupported("الملف يتجاوز الحد المسموح للمعاينة.")
        }

        val rule = rules.firstOrNull { finding.id.startsWith(it.idPrefix) }
            ?: return unsupported("لا توجد قاعدة إصلاح آمن مؤكدة لهذا المؤشر؛ يلزم تعديل يدوي ومراجعة.")
        val suffix = finding.id.removePrefix(rule.idPrefix)
        val separator = suffix.lastIndexOf(':')
        if (separator <= 0) return unsupported("تعذر تحديد موضع المؤشر بدقة.")
        val findingPath = suffix.substring(0, separator)
        val lineNumber = suffix.substring(separator + 1).toIntOrNull()
            ?: return unsupported("رقم السطر في المؤشر غير صالح.")
        if (findingPath != path) return unsupported("المؤشر لا يطابق الملف المحدد.")

        val lines = file.content.split('\n').toMutableList()
        val index = lineNumber - 1
        if (index !in lines.indices) return unsupported("السطر المشار إليه غير موجود.")
        val originalLine = lines[index]
        val matches = rule.attributePattern.findAll(originalLine).toList()
        if (matches.size != 1) {
            return unsupported("لم يُعثر على خاصية واحدة مطابقة تماماً في السطر؛ لن يتم اقتراح تغيير تخميني.")
        }

        val match = matches.single()
        val replacement = match.groupValues[1] + match.groupValues[2] + "false" + match.groupValues[2]
        lines[index] = originalLine.replaceRange(match.range, replacement)
        val proposed = lines.joinToString("\n")
        if (proposed == file.content) return unsupported("المعاينة لا تنتج تغييراً فعلياً.")
        if (proposed.toByteArray(Charsets.UTF_8).size > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) {
            return unsupported("المحتوى المقترح يتجاوز الحد المسموح.")
        }

        val diff = ProjectTextDiff.compare(file.content, proposed)
        if (diff.truncated) return unsupported("تعذر عرض فرق محدود لهذا الملف؛ لم يتم إنشاء معاينة.")
        val remaining = ProjectSecurityScanner.scan(listOf(ProjectSourceFile(path, proposed)))
            .count { it.id.startsWith("${rule.idPrefix}$path:") }

        return ProjectRemediationPreview(
            supported = true,
            relativePath = path,
            originalContent = file.content,
            proposedContent = proposed,
            diff = diff,
            remainingMatchingFindings = remaining,
            reason = rule.explanation
        )
    }
}

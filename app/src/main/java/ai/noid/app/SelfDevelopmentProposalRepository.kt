package ai.noid.app

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

/**
 * Durable proposal/review boundary for self-development.
 *
 * This repository never opens, writes, executes, or uploads project files.
 * Applying an approved proposal requires a separate SAF + audit + confirmation
 * workflow and is intentionally not implemented here.
 */
class SelfDevelopmentProposalRepository(
    private val dao: SelfDevelopmentProposalDao
) {
    val pending: Flow<List<SelfDevelopmentProposal>> = dao.observePending()

    suspend fun propose(
        title: String,
        rationale: String,
        relativePath: String,
        originalContent: String,
        proposedContent: String
    ): Long {
        val safeTitle = title.trim()
        val safeRationale = rationale.trim()
        val normalizedPath = ProjectFilePolicy.normalizeRelativePath(relativePath)
            ?: throw IllegalArgumentException("مسار الملف المقترح غير صالح.")
        require(normalizedPath == relativePath) { "يجب استخدام مسار نسبي منسق." }
        require(ProjectFilePolicy.isEditableTextFile(normalizedPath.substringAfterLast('/'))) {
            "نوع الملف غير مدعوم لمقترحات التطوير."
        }
        require(safeTitle.isNotEmpty()) { "عنوان التغيير مطلوب." }
        require(safeTitle.length <= MAX_TITLE_CHARS) { "عنوان التغيير طويل جداً." }
        require(safeRationale.isNotEmpty()) { "سبب التغيير مطلوب." }
        require(safeRationale.length <= MAX_RATIONALE_CHARS) { "سبب التغيير طويل جداً." }
        require(originalContent.toByteArray(Charsets.UTF_8).size <= MAX_CONTENT_BYTES) {
            "المحتوى الأصلي يتجاوز حد 2 ميغابايت."
        }
        require(proposedContent.toByteArray(Charsets.UTF_8).size <= MAX_CONTENT_BYTES) {
            "المحتوى المقترح يتجاوز حد 2 ميغابايت."
        }
        require(originalContent != proposedContent) { "لا توجد تغييرات لحفظها كمقترح." }
        require(proposedContent.isNotEmpty()) { "لا يدعم هذا المسار اقتراح حذف الملف." }

        return dao.insert(
            SelfDevelopmentProposal(
                title = safeTitle,
                rationale = safeRationale,
                relativePath = normalizedPath,
                originalContent = originalContent,
                proposedContent = proposedContent,
                originalSha256 = sha256(originalContent),
                proposedSha256 = sha256(proposedContent),
                status = SelfDevelopmentProposal.STATUS_PENDING_REVIEW
            )
        )
    }

    suspend fun approve(id: Long): Boolean {
        require(id > 0) { "معرّف المقترح غير صالح." }
        return dao.approvePending(id, System.currentTimeMillis()) == 1
    }

    suspend fun reject(id: Long): Boolean {
        require(id > 0) { "معرّف المقترح غير صالح." }
        return dao.rejectPending(id, System.currentTimeMillis()) == 1
    }

    suspend fun recent(limit: Int = MAX_RECENT): List<SelfDevelopmentProposal> {
        require(limit in 1..MAX_RECENT)
        return dao.getRecent(limit)
    }

    companion object {
        const val MAX_TITLE_CHARS = 120
        const val MAX_RATIONALE_CHARS = 1_000
        const val MAX_CONTENT_BYTES = 2 * 1024 * 1024
        const val MAX_RECENT = 100

        fun sha256(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }
    }
}

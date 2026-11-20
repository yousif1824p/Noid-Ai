package ai.noid.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.util.Properties

data class ProjectFileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)

data class ProjectFileBackup(
    val relativePath: String,
    val backupFile: File,
    val createdAt: Long
)

/** Access is restricted to a directory explicitly selected by the user through SAF. */
class ProjectFileRepository(
    context: Context,
    private val backupDirectory: File
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val appContext: Context = context.applicationContext

    fun list(rootTreeUri: Uri, relativeDirectory: String = ""): List<ProjectFileEntry> {
        val directory = resolve(rootTreeUri, relativeDirectory.ifBlank { null })
        if (!directory.isDirectory) throw IOException("المسار المحدد ليس مجلداً.")
        return directory.listFiles().map { child ->
            val path = listOf(relativeDirectory.trim('/'), child.name ?: "")
                .filter { it.isNotBlank() }.joinToString("/")
            ProjectFileEntry(child.name ?: "", path, child.isDirectory, child.length())
        }.sortedWith(compareByDescending<ProjectFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun readText(rootTreeUri: Uri, relativePath: String): String {
        val normalized = ProjectFilePolicy.normalizeRelativePath(relativePath)
            ?: throw IOException("مسار الملف غير صالح.")
        val file = resolve(rootTreeUri, normalized)
        if (!file.isFile || !ProjectFilePolicy.isEditableTextFile(file.name ?: "")) {
            throw IOException("نوع الملف غير مدعوم للتحرير النصي.")
        }
        if (file.length() > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) throw IOException("حجم الملف يتجاوز الحد المسموح.")
        val input = resolver.openInputStream(file.uri)
            ?: throw IOException("تعذر قراءة الملف.")
        return input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) {
                    throw IOException("الملف يتجاوز حد 2 ميغابايت أثناء القراءة.")
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    /** Backup is written to app-private storage before the selected document is replaced. */
    fun writeText(rootTreeUri: Uri, relativePath: String, content: String): ProjectFileBackup {
        val normalized = ProjectFilePolicy.normalizeRelativePath(relativePath)
            ?: throw IOException("مسار الملف غير صالح.")
        val file = resolve(rootTreeUri, normalized)
        if (!file.isFile || !ProjectFilePolicy.isEditableTextFile(file.name ?: "")) {
            throw IOException("يسمح بالتعديل على الملفات النصية المدعومة فقط.")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > ProjectFilePolicy.MAX_TEXT_FILE_BYTES ||
            file.length() > ProjectFilePolicy.MAX_TEXT_FILE_BYTES
        ) throw IOException("الملف أو المحتوى يتجاوز حد 2 ميغابايت.")

        if (!backupDirectory.exists() && !backupDirectory.mkdirs()) throw IOException("تعذر إنشاء مجلد النسخ الاحتياطية.")
        val backup = File.createTempFile("noid-project-", ".bak", backupDirectory)
        var backupComplete = false
        try {
            resolver.openInputStream(file.uri)?.use { input ->
                backup.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) {
                            throw IOException("الملف أكبر من الحد المسموح أثناء النسخ الاحتياطي.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("تعذر إنشاء نسخة احتياطية.")
            persistBackupMetadata(ProjectFileBackup(normalized, backup, backup.lastModified()))
            backupComplete = true
            resolver.openOutputStream(file.uri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IOException("تعذر حفظ التعديل.")
        } catch (failure: Exception) {
            // Keep the backup even when writing fails; providers may have partially
            // truncated the destination before reporting an I/O error.
            if (backupComplete) {
                try {
                    if (backup.isFile && backup.length() <= ProjectFilePolicy.MAX_TEXT_FILE_BYTES) {
                        resolver.openOutputStream(file.uri, "wt")?.use { output ->
                            backup.inputStream().use { input -> input.copyTo(output) }
                        }
                    }
                } catch (_: Exception) {
                    // Preserve the complete backup for manual recovery if rollback fails.
                }
            } else {
                backup.delete()
                metadataFile(backup).delete()
            }
            throw IOException(
                if (backupComplete) "فشل الحفظ؛ احتُفظ بالنسخة الاحتياطية الكاملة في مساحة NOID الخاصة."
                else "فشل إنشاء النسخة الاحتياطية؛ لم يبدأ تعديل الملف.",
                failure
            )
        }
        return ProjectFileBackup(normalized, backup, backup.lastModified())
    }

    /** Returns durable backups, newest first; an optional path narrows the history. */
    fun listBackups(relativePath: String? = null): List<ProjectFileBackup> {
        if (!backupDirectory.isDirectory) return emptyList()
        val normalizedFilter = relativePath?.let {
            ProjectFilePolicy.normalizeRelativePath(it) ?: throw IOException("مسار الملف غير صالح.")
        }
        return backupDirectory.listFiles { file -> file.isFile && file.extension == "bak" }
            .orEmpty()
            .mapNotNull { backupFile -> readBackupMetadata(backupFile) }
            .filter { normalizedFilter == null || it.relativePath == normalizedFilter }
            .sortedByDescending { it.createdAt }
    }

    fun deleteBackup(backup: ProjectFileBackup): Boolean {
        val canonicalDirectory = backupDirectory.canonicalFile
        val canonicalBackup = backup.backupFile.canonicalFile
        if (canonicalBackup.parentFile != canonicalDirectory || canonicalBackup.extension != "bak") {
            throw IOException("النسخة الاحتياطية لا تنتمي إلى مساحة NOID.")
        }
        val metadataDeleted = !metadataFile(canonicalBackup).exists() || metadataFile(canonicalBackup).delete()
        val backupDeleted = !canonicalBackup.exists() || canonicalBackup.delete()
        return metadataDeleted && backupDeleted
    }

    private fun persistBackupMetadata(backup: ProjectFileBackup) {
        val metadata = Properties().apply {
            setProperty("relativePath", backup.relativePath)
            setProperty("createdAt", backup.backupFile.lastModified().toString())
        }
        val target = metadataFile(backup.backupFile)
        val temporary = File.createTempFile("noid-meta-", ".tmp", backupDirectory)
        try {
            temporary.outputStream().use { metadata.store(it, "NOID project backup") }
            if (!temporary.renameTo(target)) throw IOException("تعذر حفظ بيانات النسخة الاحتياطية.")
        } finally {
            temporary.delete()
        }
    }

    private fun readBackupMetadata(backupFile: File): ProjectFileBackup? {
        return try {
            val metadata = Properties().apply { metadataFile(backupFile).inputStream().use { load(it) } }
            val path = metadata.getProperty("relativePath") ?: return null
            val normalized = ProjectFilePolicy.normalizeRelativePath(path) ?: return null
            val createdAt = metadata.getProperty("createdAt")?.toLongOrNull() ?: backupFile.lastModified()
            ProjectFileBackup(normalized, backupFile, createdAt)
        } catch (_: Exception) {
            null
        }
    }

    private fun metadataFile(backupFile: File): File = File(backupFile.parentFile, backupFile.name + ".meta")

    fun restoreBackup(rootTreeUri: Uri, backup: ProjectFileBackup): ProjectFileBackup {
        if (!backup.backupFile.isFile || backup.backupFile.length() > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) {
            throw IOException("النسخة الاحتياطية غير صالحة.")
        }
        val file = resolve(rootTreeUri, backup.relativePath)
        if (!file.isFile || !ProjectFilePolicy.isEditableTextFile(file.name ?: "")) {
            throw IOException("ملف الاستعادة غير صالح.")
        }
        val restoreContent = backup.backupFile.readText(Charsets.UTF_8)
        // writeText first backs up the current document, so restoration itself is reversible.
        return writeText(rootTreeUri, backup.relativePath, restoreContent)
    }

    fun createTextFile(rootTreeUri: Uri, relativePath: String, content: String): Uri {
        val normalized = ProjectFilePolicy.normalizeRelativePath(relativePath)
            ?: throw IOException("مسار الملف غير صالح.")
        val name = normalized.substringAfterLast('/')
        if (!ProjectFilePolicy.isEditableTextFile(name)) throw IOException("امتداد الملف غير مدعوم.")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) throw IOException("الملف أكبر من الحد المسموح.")
        val parentPath = normalized.substringBeforeLast('/', "")
        val parent = resolve(rootTreeUri, parentPath.ifBlank { null })
        if (!parent.isDirectory) throw IOException("المجلد الأب غير صالح.")
        if (parent.findFile(name) != null) throw IOException("يوجد ملف بهذا الاسم بالفعل.")
        val created = parent.createFile(mimeTypeFor(name), name) ?: throw IOException("تعذر إنشاء الملف.")
        try {
            resolver.openOutputStream(created.uri, "wt")?.use { it.write(bytes) }
                ?: throw IOException("تعذر كتابة الملف الجديد.")
        } catch (failure: Exception) {
            created.delete()
            throw failure
        }
        return created.uri
    }

    fun rename(rootTreeUri: Uri, relativePath: String, newName: String): Boolean {
        val normalized = ProjectFilePolicy.normalizeRelativePath(relativePath)
            ?: throw IOException("مسار الملف غير صالح.")
        if (newName.isBlank() || newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
            throw IOException("اسم جديد غير صالح.")
        }
        if (newName.length > 128 || ProjectFilePolicy.containsUnsafePathCharacters(newName)) {
            throw IOException("اسم الملف طويل جداً أو يحتوي على محارف غير مسموحة.")
        }
        val target = resolve(rootTreeUri, normalized)
        if (!target.isFile || !ProjectFilePolicy.isEditableTextFile(target.name ?: "") ||
            !ProjectFilePolicy.isEditableTextFile(newName)
        ) throw IOException("إعادة التسمية متاحة لملفات النص المدعومة فقط.")
        val parentPath = normalized.substringBeforeLast('/', "")
        val parent = resolve(rootTreeUri, parentPath.ifBlank { null })
        val existing = parent.findFile(newName)
        if (existing != null && existing.uri != target.uri) {
            throw IOException("يوجد ملف آخر بهذا الاسم بالفعل.")
        }
        if (target.name == newName) return true
        return target.renameTo(newName)
    }

    private fun resolve(rootTreeUri: Uri, relativePath: String?): DocumentFile {
        val root = DocumentFile.fromTreeUri(appContext, rootTreeUri)
            ?: throw IOException("مجلد المشروع غير متاح.")
        if (relativePath.isNullOrBlank()) return root
        val normalized = ProjectFilePolicy.normalizeRelativePath(relativePath)
            ?: throw IOException("مسار الملف غير صالح.")
        var current = root
        for (segment in normalized.split('/')) {
            current = current.findFile(segment) ?: throw IOException("المسار غير موجود: $segment")
        }
        return current
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "xml" -> "application/xml"
        "json" -> "application/json"
        "html" -> "text/html"
        "css" -> "text/css"
        else -> "text/plain"
    }
}

package ai.noid.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Properties

data class ProjectFileEntry(val name: String, val relativePath: String, val isDirectory: Boolean, val sizeBytes: Long)
data class ProjectFileBackup(val relativePath: String, val backupFile: File, val createdAt: Long)

/** Access is restricted to a directory explicitly selected by the user through SAF. */
class ProjectFileRepository(context: Context, private val backupDirectory: File) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val appContext: Context = context.applicationContext

    fun list(rootTreeUri: Uri, relativeDirectory: String = ""): List<ProjectFileEntry> {
        val directory = resolve(rootTreeUri, relativeDirectory.ifBlank { null })
        if (!directory.isDirectory) throw IOException("المسار المحدد ليس مجلداً.")
        return directory.listFiles().map { child ->
            val path = listOf(relativeDirectory.trim('/'), child.name ?: "").filter { it.isNotBlank() }.joinToString("/")
            ProjectFileEntry(child.name ?: "", path, child.isDirectory, child.length())
        }.sortedWith(compareByDescending<ProjectFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun readText(rootTreeUri: Uri, relativePath: String): String {
        val normalized = ProjectFilePolicy.normalizeRelativePath(relativePath) ?: throw IOException("مسار الملف غير صالح.")
        val file = resolve(rootTreeUri, normalized)
        if (!file.isFile || !ProjectFilePolicy.isEditableTextFile(file.name ?: "")) throw IOException("نوع الملف غير مدعوم للتحرير النصي.")
        if (file.length() > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) throw IOException("حجم الملف يتجاوز الحد المسموح.")
        val input = resolver.openInputStream(file.uri) ?: throw IOException("تعذر قراءة الملف.")
        return input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) throw IOException("الملف يتجاوز حد 2 ميغابايت أثناء القراءة.")
                output.write(buffer, 0, count)
            }
            try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
            } catch (failure: java.nio.charset.CharacterCodingException) {
                throw IOException("ترميز الملف ليس UTF-8 صالحاً.", failure)
            }
        }
    }

    fun verifySourceUnchanged(rootTreeUri: Uri, relativePath: String, expectedSha256: String): SelfDevelopmentSourceFreshnessResult =
        SelfDevelopmentSourceFreshness.verify(expectedSha256) { readText(rootTreeUri, relativePath) }

    /** Backup is written to app-private storage before the selected document is replaced. */
    fun writeText(rootTreeUri: Uri, relativePath: String, content: String): ProjectFileBackup {
        val normalized = ProjectFilePolicy.normalizeRelativePath(relativePath) ?: throw IOException("مسار الملف غير صالح.")
        val file = resolve(rootTreeUri, normalized)
        if (!file.isFile || !ProjectFilePolicy.isEditableTextFile(file.name ?: "")) throw IOException("يسمح بالتعديل على الملفات النصية المدعومة فقط.")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > ProjectFilePolicy.MAX_TEXT_FILE_BYTES || file.length() > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) {
            throw IOException("الملف أو المحتوى يتجاوز حد 2 ميغابايت.")
        }
        ProjectBackupDirectoryPolicy.ensure(backupDirectory, createIfMissing = true)
        val backupPath = Files.createTempFile(backupDirectory.toPath(), "noid-project-", ".bak")
        val backup = backupPath.toFile()
        var backupComplete = false
        try {
            resolver.openInputStream(file.uri)?.use { input ->
                Files.newOutputStream(backupPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) throw IOException("الملف أكبر من الحد المسموح أثناء النسخ الاحتياطي.")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("تعذر إنشاء نسخة احتياطية.")
            // Refuse to replace a document whose original bytes cannot be restored losslessly as UTF-8.
            ProjectFileBackupTextEncoding.decode(Files.readAllBytes(backupPath))
            persistBackupMetadata(ProjectFileBackup(normalized, backup, backup.lastModified()))
            backupComplete = true
            resolver.openOutputStream(file.uri, "wt")?.use { output -> output.write(bytes); output.flush() }
                ?: throw IOException("تعذر حفظ التعديل.")
        } catch (failure: Exception) {
            if (backupComplete) {
                try {
                    if (backup.isFile && backup.length() <= ProjectFilePolicy.MAX_TEXT_FILE_BYTES) {
                        resolver.openOutputStream(file.uri, "wt")?.use { output -> backup.inputStream().use { it.copyTo(output) } }
                    }
                } catch (_: Exception) {
                    // Keep the complete backup for manual recovery if rollback fails.
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

    fun listBackups(relativePath: String? = null): List<ProjectFileBackup> {
        if (!ProjectBackupDirectoryPolicy.ensure(backupDirectory, createIfMissing = false)) return emptyList()
        val normalizedFilter = relativePath?.let { ProjectFilePolicy.normalizeRelativePath(it) ?: throw IOException("مسار الملف غير صالح.") }
        return backupDirectory.listFiles().orEmpty()
            .filter { ProjectBackupDirectoryPolicy.isOwnedRegularFile(backupDirectory, it, "bak") }
            .mapNotNull { readBackupMetadata(it) }
            .filter { normalizedFilter == null || it.relativePath == normalizedFilter }
            .sortedByDescending { it.createdAt }
    }

    fun deleteBackup(backup: ProjectFileBackup): Boolean {
        if (!isValidBackupRecord(backup)) throw IOException("النسخة الاحتياطية لا تنتمي إلى مساحة NOID أو بياناتها غير صالحة.")
        val metadata = metadataFile(backup.backupFile)
        val metadataExists = Files.exists(metadata.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (metadataExists && !ProjectBackupDirectoryPolicy.isOwnedRegularFile(backupDirectory, metadata, "meta")) {
            throw IOException("بيانات النسخة الاحتياطية ليست ملفاً آمناً.")
        }
        val metadataDeleted = !metadataExists || Files.deleteIfExists(metadata.toPath())
        val backupDeleted = Files.deleteIfExists(backup.backupFile.toPath())
        return metadataDeleted && backupDeleted
    }

    private fun persistBackupMetadata(backup: ProjectFileBackup) {
        val metadata = Properties().apply {
            setProperty("relativePath", backup.relativePath)
            setProperty("createdAt", backup.backupFile.lastModified().toString())
        }
        val target = metadataFile(backup.backupFile)
        ProjectBackupDirectoryPolicy.ensure(backupDirectory, createIfMissing = true)
        val temporaryPath = Files.createTempFile(backupDirectory.toPath(), "noid-meta-", ".tmp")
        val temporary = temporaryPath.toFile()
        try {
            Files.newOutputStream(temporaryPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
                metadata.store(it, "NOID project backup")
            }
            if (!temporary.renameTo(target)) throw IOException("تعذر حفظ بيانات النسخة الاحتياطية.")
        } finally {
            temporary.delete()
        }
    }

    private fun readBackupMetadata(backupFile: File): ProjectFileBackup? {
        if (!ProjectBackupDirectoryPolicy.isOwnedRegularFile(backupDirectory, backupFile, "bak")) return null
        val metadataPath = metadataFile(backupFile)
        if (!ProjectBackupDirectoryPolicy.isOwnedRegularFile(backupDirectory, metadataPath, "meta")) return null
        return try {
            val metadata = Properties().apply { metadataPath.inputStream().use { load(it) } }
            val path = metadata.getProperty("relativePath") ?: return null
            val normalized = ProjectFilePolicy.normalizeRelativePath(path) ?: return null
            val createdAt = metadata.getProperty("createdAt")?.toLongOrNull() ?: backupFile.lastModified()
            ProjectFileBackup(normalized, backupFile, createdAt)
        } catch (_: Exception) { null }
    }

    private fun isValidBackupRecord(backup: ProjectFileBackup): Boolean {
        if (!ProjectBackupDirectoryPolicy.isOwnedRegularFile(backupDirectory, backup.backupFile, "bak")) return false
        val stored = readBackupMetadata(backup.backupFile) ?: return false
        val normalized = ProjectFilePolicy.normalizeRelativePath(backup.relativePath) ?: return false
        return stored.relativePath == normalized && stored.createdAt == backup.createdAt &&
            stored.backupFile.canonicalFile == backup.backupFile.canonicalFile
    }

    private fun metadataFile(backupFile: File): File = File(backupFile.parentFile, backupFile.name + ".meta")

    fun restoreBackup(rootTreeUri: Uri, backup: ProjectFileBackup): ProjectFileBackup {
        if (!isValidBackupRecord(backup) || backup.backupFile.length() > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) {
            throw IOException("النسخة الاحتياطية غير صالحة أو لا تنتمي إلى مساحة NOID.")
        }
        val file = resolve(rootTreeUri, backup.relativePath)
        if (!file.isFile || !ProjectFilePolicy.isEditableTextFile(file.name ?: "")) throw IOException("ملف الاستعادة غير صالح.")
        val restoreContent = ProjectFileBackupTextEncoding.decode(Files.readAllBytes(backup.backupFile.toPath()))
        return writeText(rootTreeUri, backup.relativePath, restoreContent)
    }

    fun createTextFile(rootTreeUri: Uri, relativePath: String, content: String): Uri {
        val normalized = ProjectFilePolicy.normalizeRelativePath(relativePath) ?: throw IOException("مسار الملف غير صالح.")
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
            resolver.openOutputStream(created.uri, "wt")?.use { it.write(bytes) } ?: throw IOException("تعذر كتابة الملف الجديد.")
        } catch (failure: Exception) { created.delete(); throw failure }
        return created.uri
    }

    fun rename(rootTreeUri: Uri, relativePath: String, newName: String): Boolean {
        val normalized = ProjectFilePolicy.normalizeRelativePath(relativePath) ?: throw IOException("مسار الملف غير صالح.")
        if (newName.isBlank() || newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
            throw IOException("اسم جديد غير صالح.")
        }
        if (newName.length > 128 || ProjectFilePolicy.containsUnsafePathCharacters(newName)) throw IOException("اسم الملف طويل جداً أو يحتوي على محارف غير مسموحة.")
        val target = resolve(rootTreeUri, normalized)
        if (!target.isFile || !ProjectFilePolicy.isEditableTextFile(target.name ?: "") || !ProjectFilePolicy.isEditableTextFile(newName)) {
            throw IOException("إعادة التسمية متاحة لملفات النص المدعومة فقط.")
        }
        val parentPath = normalized.substringBeforeLast('/', "")
        val parent = resolve(rootTreeUri, parentPath.ifBlank { null })
        val existing = parent.findFile(newName)
        if (existing != null && existing.uri != target.uri) throw IOException("يوجد ملف آخر بهذا الاسم بالفعل.")
        if (target.name == newName) return true
        return target.renameTo(newName)
    }

    private fun resolve(rootTreeUri: Uri, relativePath: String?): DocumentFile {
        val root = DocumentFile.fromTreeUri(appContext, rootTreeUri) ?: throw IOException("مجلد المشروع غير متاح.")
        if (relativePath.isNullOrBlank()) return root
        val normalized = ProjectFilePolicy.normalizeRelativePath(relativePath) ?: throw IOException("مسار الملف غير صالح.")
        var current = root
        for (segment in normalized.split('/')) current = current.findFile(segment) ?: throw IOException("المسار غير موجود: $segment")
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

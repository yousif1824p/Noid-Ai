package ai.noid.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Verifies the app-private backup directory without following symbolic links. */
object ProjectBackupDirectoryPolicy {
    fun ensure(directory: File, createIfMissing: Boolean): Boolean {
        val path = directory.toPath().toAbsolutePath().normalize()
        try {
            val requestedParent = path.parent
                ?: throw IOException("مجلد النسخ الاحتياطية غير صالح.")
            val canonicalParent = directory.parentFile?.canonicalFile?.toPath()?.normalize()
                ?: throw IOException("مجلد النسخ الاحتياطية غير صالح.")
            if (canonicalParent != requestedParent) {
                throw IOException("مسار مجلد النسخ الاحتياطية يمر عبر رابط رمزي.")
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!createIfMissing) return false
                Files.createDirectories(path)
            }
            if (Files.isSymbolicLink(path) ||
                !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
                directory.canonicalFile.toPath().normalize() != path
            ) throw IOException("مجلد النسخ الاحتياطية ليس مجلداً خاصاً آمناً.")
            return true
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("تعذر التحقق من مجلد النسخ الاحتياطية.", failure)
        }
    }

    /** Accepts only a real regular file directly inside the verified backup directory. */
    fun isOwnedRegularFile(directory: File, file: File, expectedExtension: String): Boolean {
        return try {
            if (!ensure(directory, createIfMissing = false)) return false
            if (file.extension != expectedExtension) return false
            val directoryPath = directory.canonicalFile.toPath().normalize()
            val filePath = file.toPath().toAbsolutePath().normalize()
            if (filePath.parent != directoryPath) return false
            if (Files.isSymbolicLink(filePath) ||
                !Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)
            ) return false
            file.canonicalFile.toPath().normalize() == filePath
        } catch (_: Exception) {
            false
        }
    }
}

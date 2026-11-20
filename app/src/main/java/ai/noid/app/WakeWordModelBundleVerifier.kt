package ai.noid.app

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Verifies the actual imported model bytes without loading the model into memory.
 * The caller must still validate the ONNX/TFLite graph and runtime compatibility.
 */
internal object WakeWordModelBundleVerifier {
    enum class Result {
        VERIFIED,
        INVALID_MANIFEST,
        MISSING_FILE,
        UNSAFE_PATH,
        SIZE_MISMATCH,
        DIGEST_MISMATCH,
        IO_ERROR
    }

    private const val BUFFER_BYTES = 64 * 1024

    fun verifyModelFile(
        modelsDirectory: File,
        candidate: File,
        manifest: WakeWordModelManifest
    ): Result {
        if (WakeWordModelManifestPolicy.validate(manifest) != null) {
            return Result.INVALID_MANIFEST
        }
        if (candidate.name != manifest.modelFileName) return Result.UNSAFE_PATH
        if (!candidate.exists()) return Result.MISSING_FILE

        val root = try {
            modelsDirectory.canonicalFile
        } catch (_: IOException) {
            return Result.IO_ERROR
        } catch (_: SecurityException) {
            return Result.IO_ERROR
        }
        val actual = try {
            candidate.canonicalFile
        } catch (_: IOException) {
            return Result.IO_ERROR
        } catch (_: SecurityException) {
            return Result.IO_ERROR
        }

        if (actual.parentFile != root || actual.name != manifest.modelFileName ||
            !actual.isFile || !actual.canRead()
        ) return Result.UNSAFE_PATH
        if (actual.length() != manifest.modelBytes ||
            !ModelFileImportPolicy.isSupportedSize(actual.length())
        ) return Result.SIZE_MISMATCH

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            actual.inputStream().buffered(BUFFER_BYTES).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    totalBytes += count.toLong()
                    if (totalBytes > manifest.modelBytes ||
                        totalBytes > WakeWordModelManifestPolicy.MAX_MODEL_BYTES
                    ) return Result.SIZE_MISMATCH
                    digest.update(buffer, 0, count)
                }
            }
            if (totalBytes != manifest.modelBytes || actual.length() != totalBytes) {
                Result.SIZE_MISMATCH
            } else {
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (actualHash.equals(manifest.modelSha256, ignoreCase = true)) {
                    Result.VERIFIED
                } else {
                    Result.DIGEST_MISMATCH
                }
            }
        } catch (_: IOException) {
            Result.IO_ERROR
        } catch (_: SecurityException) {
            Result.IO_ERROR
        }
    }
}

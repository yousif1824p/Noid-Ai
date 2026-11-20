package ai.noid.app

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Validates a complete on-disk wake-word bundle before it can be considered
 * eligible for a later runtime loader. This class never loads a model or audio.
 */
internal object WakeWordModelBundleImporter {
    enum class Status {
        READY_FOR_RUNTIME_VALIDATION,
        INVALID_MANIFEST,
        INVALID_PROFILE,
        INVALID_DSP_ARTIFACT,
        UNSAFE_BUNDLE_PATH,
        MISSING_MODEL,
        MODEL_SIZE_MISMATCH,
        MODEL_DIGEST_MISMATCH,
        MISSING_PROFILE,
        PROFILE_SIZE_MISMATCH,
        PROFILE_DIGEST_MISMATCH,
        MISSING_DSP_DESCRIPTOR,
        MISSING_DSP_PAYLOAD,
        IO_ERROR
    }

    data class Result(
        val status: Status,
        val manifest: WakeWordModelManifest? = null
    )

    private const val MANIFEST_FILE_NAME = "manifest.json"
    private const val PROFILE_MAX_BYTES = 64 * 1024L
    private const val DSP_DESCRIPTOR_MAX_BYTES = 16 * 1024L
    private const val DSP_PAYLOAD_MAX_BYTES = 1024 * 1024L
    private val profileIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")

    fun inspect(modelsDirectory: File, manifestFile: File): Result {
        val root = canonicalDirectory(modelsDirectory) ?: return Result(Status.IO_ERROR)
        if (manifestFile.name != MANIFEST_FILE_NAME ||
            !isDirectChild(root, manifestFile)
        ) return Result(Status.UNSAFE_BUNDLE_PATH)

        val canonicalManifest = canonicalFile(manifestFile)
            ?: return Result(Status.IO_ERROR)
        if (!canonicalManifest.isFile || !canonicalManifest.canRead()) {
            return Result(Status.INVALID_MANIFEST)
        }

        val manifest = try {
            canonicalManifest.inputStream().use { WakeWordModelManifestJsonParser.parse(it) }
        } catch (_: IOException) {
            return Result(Status.IO_ERROR)
        } catch (_: SecurityException) {
            return Result(Status.IO_ERROR)
        } ?: return Result(Status.INVALID_MANIFEST)

        val modelFile = File(root, manifest.modelFileName)
        when (WakeWordModelBundleVerifier.verifyModelFile(root, modelFile, manifest)) {
            WakeWordModelBundleVerifier.Result.VERIFIED -> Unit
            WakeWordModelBundleVerifier.Result.MISSING_FILE -> return Result(Status.MISSING_MODEL)
            WakeWordModelBundleVerifier.Result.SIZE_MISMATCH -> return Result(Status.MODEL_SIZE_MISMATCH)
            WakeWordModelBundleVerifier.Result.DIGEST_MISMATCH -> return Result(Status.MODEL_DIGEST_MISMATCH)
            WakeWordModelBundleVerifier.Result.INVALID_MANIFEST -> return Result(Status.INVALID_MANIFEST)
            WakeWordModelBundleVerifier.Result.UNSAFE_PATH -> return Result(Status.UNSAFE_BUNDLE_PATH)
            WakeWordModelBundleVerifier.Result.IO_ERROR -> return Result(Status.IO_ERROR)
        }

        if (!profileIdPattern.matches(manifest.preprocessingProfileId)) {
            return Result(Status.INVALID_MANIFEST)
        }
        val profileFile = File(root, "${manifest.preprocessingProfileId}.json")
        if (!isDirectChild(root, profileFile)) return Result(Status.UNSAFE_BUNDLE_PATH)
        val canonicalProfile = canonicalFile(profileFile) ?: return Result(Status.IO_ERROR)
        if (!canonicalProfile.exists() || !canonicalProfile.isFile || !canonicalProfile.canRead()) {
            return Result(Status.MISSING_PROFILE)
        }
        val profileBytes = readBounded(canonicalProfile, PROFILE_MAX_BYTES)
            ?: return Result(Status.PROFILE_SIZE_MISMATCH)
        val actualProfileDigest = sha256(profileBytes)
        if (!actualProfileDigest.equals(manifest.preprocessingProfileSha256, ignoreCase = true)) {
            return Result(Status.PROFILE_DIGEST_MISMATCH)
        }
        val profileText = strictUtf8(profileBytes) ?: return Result(Status.INVALID_PROFILE)
        val profile = WakeWordPreprocessingProfileParser.parse(profileText, manifest)
            ?: return Result(Status.INVALID_PROFILE)

        val descriptorFile = File(root, "${manifest.preprocessingProfileId}.dsp.json")
        val payloadFile = File(root, "${manifest.preprocessingProfileId}.dsp.bin")
        if (!isDirectChild(root, descriptorFile) || !isDirectChild(root, payloadFile)) {
            return Result(Status.UNSAFE_BUNDLE_PATH)
        }
        val canonicalDescriptor = canonicalFile(descriptorFile) ?: return Result(Status.IO_ERROR)
        val canonicalPayload = canonicalFile(payloadFile) ?: return Result(Status.IO_ERROR)
        if (!canonicalDescriptor.exists() || !canonicalDescriptor.isFile || !canonicalDescriptor.canRead()) {
            return Result(Status.MISSING_DSP_DESCRIPTOR)
        }
        if (!canonicalPayload.exists() || !canonicalPayload.isFile || !canonicalPayload.canRead()) {
            return Result(Status.MISSING_DSP_PAYLOAD)
        }
        val descriptorBytes = readBounded(canonicalDescriptor, DSP_DESCRIPTOR_MAX_BYTES)
            ?: return Result(Status.INVALID_DSP_ARTIFACT)
        val payloadBytes = readBounded(canonicalPayload, DSP_PAYLOAD_MAX_BYTES)
            ?: return Result(Status.INVALID_DSP_ARTIFACT)
        val descriptorText = strictUtf8(descriptorBytes)
            ?: return Result(Status.INVALID_DSP_ARTIFACT)
        if (WakeWordDspArtifactParser.parse(
                descriptorText, payloadBytes, manifest.preprocessingProfileId, profile, manifest
            ) == null
        ) return Result(Status.INVALID_DSP_ARTIFACT)

        return Result(Status.READY_FOR_RUNTIME_VALIDATION, manifest)
    }

    private fun readBounded(file: File, maxBytes: Long): ByteArray? {
        return try {
            val before = file.length()
            if (before !in 1..maxBytes) return null
            val bytes = file.readBytes()
            if (bytes.size.toLong() != before || file.length() != before) null else bytes
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun canonicalDirectory(directory: File): File? = try {
        directory.canonicalFile.takeIf { it.isDirectory && it.canRead() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun canonicalFile(file: File): File? = try {
        file.canonicalFile
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun isDirectChild(root: File, file: File): Boolean =
        canonicalFile(file)?.parentFile == root
}

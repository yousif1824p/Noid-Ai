package ai.noid.app

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WakeWordModelBundleVerifierTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun manifest(bytes: ByteArray, name: String = "noid-ar-wake.onnx") =
        WakeWordModelManifest(
            schemaVersion = 1, modelFileName = name, modelSha256 = sha256(bytes),
            modelBytes = bytes.size.toLong(), wakePhrase = "يا NOID", languageTag = "ar-IQ",
            sampleRateHz = 16_000, channels = 1, audioWindowSamples = 16_000,
            fftSize = 512, windowSamples = 400, hopSamples = 160, centerPaddingSamples = 256,
            melBins = 40, frameCount = 101, tensorBatch = 1, tensorMelBins = 40,
            tensorFrames = 101,
            preprocessingOperations = listOf("highpass_filter", "log_mel", "cmn"),
            preprocessingProfileId = "profile-v1",
            preprocessingProfileSha256 = "b".repeat(64),
            modelInputName = "audio_features", modelOutputName = "wake_logit",
            threshold = 0.82f, consecutiveFrames = 2, refractoryMillis = 700,
            licenseId = "Apache-2.0", exporterVersion = "1.0"
        )

    @Test fun verifiesActualBytesUsingStreamingDigest() {
        val root = temporaryFolder.newFolder("models")
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val model = File(root, "noid-ar-wake.onnx").apply { writeBytes(bytes) }

        assertEquals(
            WakeWordModelBundleVerifier.Result.VERIFIED,
            WakeWordModelBundleVerifier.verifyModelFile(root, model, manifest(bytes))
        )
    }

    @Test fun rejectsModifiedBytesEvenWhenLengthMatches() {
        val root = temporaryFolder.newFolder("models")
        val original = "model-weights".toByteArray()
        val model = File(root, "noid-ar-wake.onnx").apply {
            writeBytes("model-weighXs".toByteArray())
        }

        assertEquals(
            WakeWordModelBundleVerifier.Result.DIGEST_MISMATCH,
            WakeWordModelBundleVerifier.verifyModelFile(root, model, manifest(original))
        )
    }

    @Test fun rejectsDeclaredSizeMismatch() {
        val root = temporaryFolder.newFolder("models")
        val bytes = "weights".toByteArray()
        val model = File(root, "noid-ar-wake.onnx").apply { writeBytes(bytes) }

        assertEquals(
            WakeWordModelBundleVerifier.Result.SIZE_MISMATCH,
            WakeWordModelBundleVerifier.verifyModelFile(
                root, model, manifest(bytes).copy(modelBytes = bytes.size.toLong() + 1)
            )
        )
    }

    @Test fun rejectsFilesOutsideTheSelectedPrivateDirectory() {
        val root = temporaryFolder.newFolder("models")
        val outside = temporaryFolder.newFile("noid-ar-wake.onnx").apply {
            writeText("weights")
        }
        val bytes = outside.readBytes()

        assertEquals(
            WakeWordModelBundleVerifier.Result.UNSAFE_PATH,
            WakeWordModelBundleVerifier.verifyModelFile(root, outside, manifest(bytes))
        )
    }

    @Test fun rejectsMissingFileAndInvalidManifest() {
        val root = temporaryFolder.newFolder("models")
        val bytes = "weights".toByteArray()
        val missing = File(root, "noid-ar-wake.onnx")

        assertEquals(
            WakeWordModelBundleVerifier.Result.MISSING_FILE,
            WakeWordModelBundleVerifier.verifyModelFile(root, missing, manifest(bytes))
        )
        assertEquals(
            WakeWordModelBundleVerifier.Result.INVALID_MANIFEST,
            WakeWordModelBundleVerifier.verifyModelFile(
                root, missing, manifest(bytes).copy(wakePhrase = "hey doc")
            )
        )
    }
}

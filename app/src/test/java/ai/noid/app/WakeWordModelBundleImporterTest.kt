package ai.noid.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WakeWordModelBundleImporterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun profileJson(): String = JSONObject().apply {
        put("phrase", "يا NOID")
        put("threshold", 0.82)
        put("sample_rate", 16000)
        put("audio_window_samples", 16000)
        put("n_mels", 40)
        put("n_fft", 512)
        put("win_length", 400)
        put("hop_length", 160)
        put("window_frames", 100)
        put("mel_fmin_hz", 0.0)
        put("mel_fmax_hz", 8000.0)
        put("window_type", "hann")
        put("preprocessing", JSONArray().put(JSONObject().apply {
            put("op", "highpass_filter")
            put("cutoff_hz", 100.0)
            put("order", 8)
            put("mains_notch_hz", JSONArray(listOf(50.0, 60.0)))
            put("note", "causal filter")
        }).put(JSONObject().apply {
            put("op", "log_mel")
            put("note", "log mel")
        }).put(JSONObject().apply {
            put("op", "cmn")
            put("note", "mean normalization")
        }))
        put("trigger", JSONObject().apply {
            put("consecutive_frames", 2)
            put("refractory_seconds", 0.7)
            put("ema_alpha", 0.5)
            put("note", "trigger gate")
        })
        put("energy_gate", JSONObject().apply {
            put("rms_threshold_dbfs", -55.0)
            put("voice_band_min_fraction", 0.15)
            put("voice_band_lo_hz", 100.0)
            put("voice_band_hi_hz", 7000.0)
            put("note", "skip silence")
        })
        put("model_size", "medium")
        put("channels", 64)
        put("n_blocks", 3)
        put("heed_export_version", 1)
    }.toString()

    private fun writeDspArtifact(root: File, profileId: String = "profile-v1"): File {
        val sectionSpecs = listOf(
            "highpass_sos" to listOf(4, 7),
            "notch_50hz_sos" to listOf(1, 7),
            "notch_60hz_sos" to listOf(1, 7),
            "hann_window" to listOf(400),
            "mel_filterbank" to listOf(40, 257)
        )
        val totalFloats = sectionSpecs.sumOf { (_, shape) -> shape.fold(1) { a, v -> a * v } }
        val payloadBuffer = ByteBuffer.allocate(totalFloats * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(totalFloats) { payloadBuffer.putFloat((it + 1) / (totalFloats + 1f)) }
        val payload = payloadBuffer.array()
        var offset = 0
        val sections = JSONArray().apply {
            sectionSpecs.forEach { (name, shape) ->
                val count = shape.fold(1) { a, v -> a * v }
                val sectionBytes = payload.copyOfRange(offset, offset + count * 4)
                put(JSONObject().apply {
                    put("name", name)
                    put("offset_bytes", offset)
                    put("element_count", count)
                    put("shape", JSONArray(shape))
                    put("sha256", sha256(sectionBytes))
                })
                offset += count * 4
            }
        }
        val descriptor = JSONObject().apply {
            put("schema_version", 1)
            put("profile_id", profileId)
            put("payload_file", "$profileId.dsp.bin")
            put("payload_bytes", payload.size)
            put("payload_sha256", sha256(payload))
            put("byte_order", "little_endian")
            put("scalar_format", "ieee754_float32")
            put("sample_rate_hz", 16000)
            put("fft_size", 512)
            put("window_samples", 400)
            put("hop_samples", 160)
            put("mel_bins", 40)
            put("sections", sections)
        }.toString()
        File(root, "$profileId.dsp.bin").writeBytes(payload)
        return File(root, "$profileId.dsp.json").apply { writeText(descriptor) }
    }

    private fun writeBundle(root: File): File {
        val model = "local model bytes".toByteArray()
        val profile = profileJson().toByteArray(Charsets.UTF_8)
        File(root, "noid-ar-wake.onnx").writeBytes(model)
        File(root, "profile-v1.json").writeBytes(profile)
        val manifest = JSONObject().apply {
            put("schemaVersion", 1)
            put("modelFileName", "noid-ar-wake.onnx")
            put("modelSha256", sha256(model))
            put("modelBytes", model.size)
            put("wakePhrase", "يا NOID")
            put("languageTag", "ar-IQ")
            put("sampleRateHz", 16000)
            put("channels", 1)
            put("audioWindowSamples", 16000)
            put("fftSize", 512)
            put("windowSamples", 400)
            put("hopSamples", 160)
            put("centerPaddingSamples", 256)
            put("melBins", 40)
            put("frameCount", 101)
            put("tensorBatch", 1)
            put("tensorMelBins", 40)
            put("tensorFrames", 101)
            put("preprocessingOperations", JSONArray(listOf("highpass_filter", "log_mel", "cmn")))
            put("preprocessingProfileId", "profile-v1")
            put("preprocessingProfileSha256", sha256(profile))
            put("modelInputName", "audio_features")
            put("modelOutputName", "wake_logit")
            put("threshold", 0.82)
            put("consecutiveFrames", 2)
            put("refractoryMillis", 700)
            put("licenseId", "Apache-2.0")
            put("exporterVersion", "1.0")
        }.toString().toByteArray(Charsets.UTF_8)
        val manifestFile = File(root, "manifest.json").apply { writeBytes(manifest) }
        writeDspArtifact(root)
        return manifestFile
    }

    @Test fun acceptsBundleOnlyAfterModelProfileAndDspArtifactsMatch() {
        val root = temporaryFolder.newFolder("bundle")
        val manifest = writeBundle(root)
        val result = WakeWordModelBundleImporter.inspect(root, manifest)
        assertEquals(WakeWordModelBundleImporter.Status.READY_FOR_RUNTIME_VALIDATION, result.status)
        assertNotNull(result.manifest)
    }

    @Test fun rejectsModifiedPreprocessingProfileDigest() {
        val root = temporaryFolder.newFolder("bundle")
        val manifest = writeBundle(root)
        File(root, "profile-v1.json").appendText(" ")
        assertEquals(WakeWordModelBundleImporter.Status.PROFILE_DIGEST_MISMATCH,
            WakeWordModelBundleImporter.inspect(root, manifest).status)
    }

    @Test fun rejectsProfileWithValidDigestButMismatchedGeometry() {
        val root = temporaryFolder.newFolder("bundle")
        val manifest = writeBundle(root)
        val invalidProfile = JSONObject(profileJson()).put("n_fft", 1024)
            .toString().toByteArray(Charsets.UTF_8)
        File(root, "profile-v1.json").writeBytes(invalidProfile)
        manifest.writeText(JSONObject(manifest.readText())
            .put("preprocessingProfileSha256", sha256(invalidProfile)).toString())
        assertEquals(WakeWordModelBundleImporter.Status.INVALID_PROFILE,
            WakeWordModelBundleImporter.inspect(root, manifest).status)
    }

    @Test fun rejectsMissingPreprocessingProfile() {
        val root = temporaryFolder.newFolder("bundle")
        val manifest = writeBundle(root)
        File(root, "profile-v1.json").delete()
        assertEquals(WakeWordModelBundleImporter.Status.MISSING_PROFILE,
            WakeWordModelBundleImporter.inspect(root, manifest).status)
    }

    @Test fun rejectsMissingDspDescriptor() {
        val root = temporaryFolder.newFolder("bundle")
        val manifest = writeBundle(root)
        File(root, "profile-v1.dsp.json").delete()
        assertEquals(WakeWordModelBundleImporter.Status.MISSING_DSP_DESCRIPTOR,
            WakeWordModelBundleImporter.inspect(root, manifest).status)
    }

    @Test fun rejectsMissingDspPayload() {
        val root = temporaryFolder.newFolder("bundle")
        val manifest = writeBundle(root)
        File(root, "profile-v1.dsp.bin").delete()
        assertEquals(WakeWordModelBundleImporter.Status.MISSING_DSP_PAYLOAD,
            WakeWordModelBundleImporter.inspect(root, manifest).status)
    }

    @Test fun rejectsDspPayloadDigestMismatch() {
        val root = temporaryFolder.newFolder("bundle")
        val manifest = writeBundle(root)
        val descriptor = File(root, "profile-v1.dsp.json")
        descriptor.writeText(JSONObject(descriptor.readText())
            .put("payload_sha256", "0".repeat(64)).toString())
        assertEquals(WakeWordModelBundleImporter.Status.INVALID_DSP_ARTIFACT,
            WakeWordModelBundleImporter.inspect(root, manifest).status)
    }

    @Test fun rejectsDspDescriptorWithWrongGeometry() {
        val root = temporaryFolder.newFolder("bundle")
        val manifest = writeBundle(root)
        val descriptor = File(root, "profile-v1.dsp.json")
        descriptor.writeText(JSONObject(descriptor.readText())
            .put("fft_size", 1024).toString())
        assertEquals(WakeWordModelBundleImporter.Status.INVALID_DSP_ARTIFACT,
            WakeWordModelBundleImporter.inspect(root, manifest).status)
    }

    @Test fun rejectsManifestOutsideBundleDirectory() {
        val root = temporaryFolder.newFolder("bundle")
        val manifest = writeBundle(root)
        val outside = temporaryFolder.newFile("manifest.json").apply { writeBytes(manifest.readBytes()) }
        assertEquals(WakeWordModelBundleImporter.Status.UNSAFE_BUNDLE_PATH,
            WakeWordModelBundleImporter.inspect(root, outside).status)
    }

    @Test fun rejectsUnsafeProfileIdentifiers() {
        val root = temporaryFolder.newFolder("bundle")
        val manifest = writeBundle(root)
        manifest.writeText(JSONObject(manifest.readText())
            .put("preprocessingProfileId", "../escape").toString())
        assertEquals(WakeWordModelBundleImporter.Status.INVALID_MANIFEST,
            WakeWordModelBundleImporter.inspect(root, manifest).status)
    }
}

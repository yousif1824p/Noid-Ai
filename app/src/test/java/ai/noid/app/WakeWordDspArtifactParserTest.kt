package ai.noid.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordDspArtifactParserTest {
    private val names = listOf(
        "highpass_sos", "notch_50hz_sos", "notch_60hz_sos", "hann_window", "mel_filterbank"
    )

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val descriptor: String,
        val payload: ByteArray,
        val profile: WakeWordPreprocessingProfileParser.Profile,
        val manifest: WakeWordModelManifest
    )

    private fun fixture(): Fixture {
        val (profile, manifest) = WakeWordModelBundleImporterTestProfile.fixture()
        val payload = ByteBuffer.allocate(42_888).order(ByteOrder.LITTLE_ENDIAN)
            .apply { repeat(10_722) { putFloat((it + 1) / 10_723f) } }.array()
        return Fixture(descriptor(profile, manifest, payload, names), payload, profile, manifest)
    }

    private fun descriptor(
        profile: WakeWordPreprocessingProfileParser.Profile,
        manifest: WakeWordModelManifest,
        payload: ByteArray,
        sectionNames: List<String>,
        offsets: List<Int>? = null,
        shapeOverrides: Map<String, List<Int>> = emptyMap()
    ): String {
        val shapes = mapOf(
            "highpass_sos" to listOf(4, 7),
            "notch_50hz_sos" to listOf(1, 7),
            "notch_60hz_sos" to listOf(1, 7),
            "hann_window" to listOf(400),
            "mel_filterbank" to listOf(40, 257)
        )
        var cursor = 0
        val sections = JSONArray()
        sectionNames.forEachIndexed { index, name ->
            val shape = shapeOverrides[name] ?: shapes[name] ?: listOf(1)
            val count = shape.fold(1) { acc, value -> acc * value }
            val offset = offsets?.get(index) ?: cursor
            val end = offset + count * 4
            val bytes = if (offset >= 0 && end <= payload.size) {
                payload.copyOfRange(offset, end)
            } else byteArrayOf()
            sections.put(JSONObject().apply {
                put("name", name)
                put("offset_bytes", offset)
                put("element_count", count)
                put("shape", JSONArray(shape))
                put("sha256", sha(bytes))
            })
            cursor += count * 4
        }
        return JSONObject().apply {
            put("schema_version", 1)
            put("profile_id", manifest.preprocessingProfileId)
            put("payload_file", "${manifest.preprocessingProfileId}.dsp.bin")
            put("payload_bytes", payload.size)
            put("payload_sha256", sha(payload))
            put("byte_order", "little_endian")
            put("scalar_format", "ieee754_float32")
            put("sample_rate_hz", profile.sampleRate)
            put("fft_size", profile.fftSize)
            put("window_samples", profile.windowSamples)
            put("hop_samples", profile.hopSamples)
            put("mel_bins", profile.melBins)
            put("sections", sections)
        }.toString()
    }

    @Test fun acceptsAllRequiredDigestCheckedFiniteContiguousSectionsInCanonicalOrder() {
        val f = fixture()
        val result = WakeWordDspArtifactParser.parse(
            f.descriptor, f.payload, "profile-v1", f.profile, f.manifest
        )
        assertEquals(names, result?.sections?.map { it.name })
    }

    @Test fun rejectsEveryStageWithWrongShapeEvenWhenCountAndDigestsAreValid() {
        val f = fixture()
        val wrongShapes = mapOf(
            "highpass_sos" to listOf(2, 14),
            "notch_50hz_sos" to listOf(7, 1),
            "notch_60hz_sos" to listOf(7, 1),
            "hann_window" to listOf(20, 20),
            "mel_filterbank" to listOf(20, 514)
        )
        wrongShapes.forEach { (stage, wrongShape) ->
            val altered = descriptor(
                f.profile, f.manifest, f.payload, names,
                shapeOverrides = mapOf(stage to wrongShape)
            )
            assertNull("accepted wrong shape for $stage", WakeWordDspArtifactParser.parse(
                altered, f.payload, "profile-v1", f.profile, f.manifest
            ))
        }
    }

    @Test fun rejectsPayloadDigestMismatch() {
        val f = fixture()
        val changed = f.payload.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(WakeWordDspArtifactParser.parse(
            f.descriptor, changed, "profile-v1", f.profile, f.manifest
        ))
    }

    @Test fun rejectsNonFiniteFloatEvenWhenDigestsAreUpdated() {
        val f = fixture()
        val payload = f.payload.copyOf()
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN)
        val descriptor = descriptor(f.profile, f.manifest, payload, names)
        assertNull(WakeWordDspArtifactParser.parse(
            descriptor, payload, "profile-v1", f.profile, f.manifest
        ))
    }

    @Test fun rejectsMissingRequiredStage() {
        val f = fixture()
        val subset = names.dropLast(1)
        val descriptor = descriptor(f.profile, f.manifest, f.payload, subset)
        assertNull(WakeWordDspArtifactParser.parse(
            descriptor, f.payload, "profile-v1", f.profile, f.manifest
        ))
    }

    @Test fun rejectsDuplicateStage() {
        val f = fixture()
        val duplicate = names.toMutableList().also { it[4] = it[3] }
        val descriptor = descriptor(f.profile, f.manifest, f.payload, duplicate)
        assertNull(WakeWordDspArtifactParser.parse(
            descriptor, f.payload, "profile-v1", f.profile, f.manifest
        ))
    }

    @Test fun rejectsNonContiguousSections() {
        val f = fixture()
        val offsets = listOf(0, 112, 140, 168, 1768).toMutableList().also { it[2] = 152 }
        val descriptor = descriptor(f.profile, f.manifest, f.payload, names, offsets)
        assertNull(WakeWordDspArtifactParser.parse(
            descriptor, f.payload, "profile-v1", f.profile, f.manifest
        ))
    }

    @Test fun rejectsProfileIdOrManifestGeometryMismatch() {
        val f = fixture()
        assertNull(WakeWordDspArtifactParser.parse(
            f.descriptor, f.payload, "different-profile", f.profile, f.manifest
        ))
        val wrongManifest = f.manifest.copy(fftSize = 1024)
        assertNull(WakeWordDspArtifactParser.parse(
            f.descriptor, f.payload, "profile-v1", f.profile, wrongManifest
        ))
    }

    @Test fun rejectsUnknownDescriptorKeys() {
        val f = fixture()
        val altered = JSONObject(f.descriptor).put("unexpected", true).toString()
        assertNull(WakeWordDspArtifactParser.parse(
            altered, f.payload, "profile-v1", f.profile, f.manifest
        ))
    }

    @Test fun rejectsOversizedDescriptor() {
        val f = fixture()
        val altered = f.descriptor.dropLast(1) + ",\"padding\":\"" + "x".repeat(17 * 1024) + "\"}"
        assertNull(WakeWordDspArtifactParser.parse(
            altered, f.payload, "profile-v1", f.profile, f.manifest
        ))
    }
}

/** Shared deterministic profile fixture without creating a file bundle. */
private object WakeWordModelBundleImporterTestProfile {
    fun fixture(): Pair<WakeWordPreprocessingProfileParser.Profile, WakeWordModelManifest> {
        val manifest = WakeWordModelManifest(
            1, "model.onnx", "0".repeat(64), 1L, "يا NOID", "ar-IQ",
            16000, 1, 16000, 512, 400, 160, 256, 40, 101,
            1, 40, 101, listOf("highpass_filter", "log_mel", "cmn"),
            "profile-v1", "0".repeat(64), "audio_features", "wake_logit",
            0.82f, 2, 700, "Apache-2.0", "1.0"
        )
        val json = JSONObject().apply {
            put("phrase", "يا NOID"); put("threshold", 0.82); put("sample_rate", 16000)
            put("audio_window_samples", 16000); put("n_mels", 40); put("n_fft", 512)
            put("win_length", 400); put("hop_length", 160); put("window_frames", 100)
            put("mel_fmin_hz", 0.0); put("mel_fmax_hz", 8000.0); put("window_type", "hann")
            put("preprocessing", JSONArray().put(JSONObject().apply {
                put("op", "highpass_filter"); put("cutoff_hz", 100.0); put("order", 8)
                put("mains_notch_hz", JSONArray(listOf(50.0, 60.0))); put("note", "filter")
            }).put(JSONObject().apply { put("op", "log_mel"); put("note", "mel") })
                .put(JSONObject().apply { put("op", "cmn"); put("note", "normalize") }))
            put("trigger", JSONObject().apply {
                put("consecutive_frames", 2); put("refractory_seconds", 0.7)
                put("ema_alpha", 0.5); put("note", "trigger")
            })
            put("energy_gate", JSONObject().apply {
                put("rms_threshold_dbfs", -55.0); put("voice_band_min_fraction", 0.15)
                put("voice_band_lo_hz", 100.0); put("voice_band_hi_hz", 7000.0); put("note", "gate")
            })
            put("model_size", "medium"); put("channels", 64); put("n_blocks", 3)
            put("heed_export_version", 1)
        }.toString()
        val profile = requireNotNull(WakeWordPreprocessingProfileParser.parse(json, manifest))
        return profile to manifest
    }
}

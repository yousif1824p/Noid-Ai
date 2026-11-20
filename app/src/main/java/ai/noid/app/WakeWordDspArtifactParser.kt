package ai.noid.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Validates the exact v1 DSP tensor contract; it does not prove coefficient provenance. */
internal object WakeWordDspArtifactParser {
    data class Section(val name: String, val offsetBytes: Int, val elementCount: Int, val shape: List<Int>)
    data class Artifact(val profileId: String, val payloadSha256: String, val sections: List<Section>)

    private const val MAX_DESCRIPTOR_BYTES = 16 * 1024
    private const val PAYLOAD_BYTES_V1 = 42_888
    private const val MAX_PAYLOAD_BYTES = 1024 * 1024
    private val digestPattern = Regex("^[a-fA-F0-9]{64}$")
    private val requiredShapes = linkedMapOf(
        "highpass_sos" to listOf(4, 7),
        "notch_50hz_sos" to listOf(1, 7),
        "notch_60hz_sos" to listOf(1, 7),
        "hann_window" to listOf(400),
        "mel_filterbank" to listOf(40, 257)
    )
    private val requiredSections = requiredShapes.keys.toList()
    private val requiredSectionSet = requiredShapes.keys

    fun parse(
        descriptorJson: String,
        payload: ByteArray,
        profileId: String,
        profile: WakeWordPreprocessingProfileParser.Profile,
        manifest: WakeWordModelManifest
    ): Artifact? {
        if (descriptorJson.isEmpty() ||
            descriptorJson.toByteArray(Charsets.UTF_8).size > MAX_DESCRIPTOR_BYTES ||
            payload.size != PAYLOAD_BYTES_V1 || payload.size > MAX_PAYLOAD_BYTES ||
            manifest.preprocessingProfileId != profileId ||
            manifest.sampleRateHz != 16_000 || profile.sampleRate != 16_000 ||
            manifest.fftSize != 512 || profile.fftSize != 512 ||
            manifest.windowSamples != 400 || profile.windowSamples != 400 ||
            manifest.hopSamples != 160 || profile.hopSamples != 160 ||
            manifest.melBins != 40 || profile.melBins != 40
        ) return null

        return try {
            val root = JSONObject(descriptorJson)
            if (root.keys().asSequence().toSet() != setOf(
                    "schema_version", "profile_id", "payload_file", "payload_bytes",
                    "payload_sha256", "byte_order", "scalar_format", "sample_rate_hz",
                    "fft_size", "window_samples", "hop_samples", "mel_bins", "sections"
                )
            ) return null

            val payloadDigest = root.exactString("payload_sha256") ?: return null
            if (root.exactInt("schema_version") != 1 ||
                root.exactString("profile_id") != profileId ||
                root.exactString("payload_file") != "$profileId.dsp.bin" ||
                root.exactInt("payload_bytes") != PAYLOAD_BYTES_V1 ||
                root.exactString("byte_order") != "little_endian" ||
                root.exactString("scalar_format") != "ieee754_float32" ||
                root.exactInt("sample_rate_hz") != 16_000 ||
                root.exactInt("fft_size") != 512 ||
                root.exactInt("window_samples") != 400 ||
                root.exactInt("hop_samples") != 160 ||
                root.exactInt("mel_bins") != 40 ||
                !digestPattern.matches(payloadDigest)
            ) return null

            val actualPayloadDigest = sha256(payload)
            if (!actualPayloadDigest.equals(payloadDigest, ignoreCase = true)) return null

            val array = root.optJSONArray("sections") ?: return null
            if (array.length() != requiredShapes.size) return null
            val sections = ArrayList<Section>(array.length())
            val names = HashSet<String>()
            var expectedOffset = 0L
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: return null
                if (item.keys().asSequence().toSet() != setOf(
                        "name", "offset_bytes", "element_count", "shape", "sha256"
                    )
                ) return null
                val name = item.exactString("name") ?: return null
                val offset = item.exactInt("offset_bytes") ?: return null
                val count = item.exactInt("element_count") ?: return null
                val digest = item.exactString("sha256") ?: return null
                val shapeArray = item.optJSONArray("shape") ?: return null
                val expectedShape = requiredShapes[name] ?: return null
                if (!names.add(name) || offset < 0 || count < 1 ||
                    shapeArray.length() !in 1..8 || !digestPattern.matches(digest)
                ) return null

                var product = 1L
                val shape = ArrayList<Int>(shapeArray.length())
                for (dimensionIndex in 0 until shapeArray.length()) {
                    val dimension = shapeArray.exactInt(dimensionIndex) ?: return null
                    if (dimension < 1 || product > Int.MAX_VALUE.toLong() / dimension.toLong()) return null
                    product *= dimension.toLong()
                    shape += dimension
                }
                if (shape != expectedShape || product != count.toLong() ||
                    count != expectedShape.fold(1) { acc, value -> acc * value } ||
                    offset.toLong() != expectedOffset
                ) return null

                val byteLength = count.toLong() * 4L
                val end = offset.toLong() + byteLength
                if (end > payload.size.toLong()) return null
                val sectionBytes = payload.copyOfRange(offset, end.toInt())
                if (!sha256(sectionBytes).equals(digest, ignoreCase = true)) return null

                val floatView = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                floatView.position(offset)
                repeat(count) { if (!floatView.float.isFinite()) return null }
                expectedOffset = end
                sections += Section(name, offset, count, shape)
            }

            if (names != requiredSectionSet || expectedOffset != PAYLOAD_BYTES_V1.toLong() ||
                sections.map { it.name } != requiredSections
            ) return null
            Artifact(profileId, actualPayloadDigest, sections)
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.exactString(key: String): String? = opt(key) as? String

    private fun JSONObject.exactInt(key: String): Int? {
        val value = opt(key) as? Number ?: return null
        val number = value.toDouble()
        if (!number.isFinite() || number % 1.0 != 0.0 ||
            number < 0.0 || number > Int.MAX_VALUE.toDouble()
        ) return null
        return number.toInt()
    }

    private fun JSONArray.exactInt(index: Int): Int? {
        val value = opt(index) as? Number ?: return null
        val number = value.toDouble()
        if (!number.isFinite() || number % 1.0 != 0.0 ||
            number < 0.0 || number > Int.MAX_VALUE.toDouble()
        ) return null
        return number.toInt()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
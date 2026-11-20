package ai.noid.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Strict, bounded JSON decoder for the wake-word manifest.
 * A decoded manifest remains untrusted until its bundle bytes are verified.
 */
internal object WakeWordModelManifestJsonParser {
    const val MAX_MANIFEST_BYTES = 32 * 1024
    private const val READ_BUFFER_BYTES = 4 * 1024

    private val requiredKeys = setOf(
        "schemaVersion", "modelFileName", "modelSha256", "modelBytes",
        "wakePhrase", "languageTag", "sampleRateHz", "channels",
        "audioWindowSamples", "fftSize", "windowSamples", "hopSamples",
        "centerPaddingSamples", "melBins", "frameCount", "tensorBatch",
        "tensorMelBins", "tensorFrames", "preprocessingOperations",
        "preprocessingProfileId", "preprocessingProfileSha256",
        "modelInputName", "modelOutputName", "threshold", "consecutiveFrames",
        "refractoryMillis", "licenseId", "exporterVersion"
    )

    /** Reads at most MAX_MANIFEST_BYTES + 1 bytes and does not close the caller's stream. */
    fun parse(input: InputStream): WakeWordModelManifest? {
        return try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size() > MAX_MANIFEST_BYTES - count) return null
                output.write(buffer, 0, count)
            }
            parseUtf8(output.toByteArray())
        } catch (_: Exception) {
            null
        }
    }

    fun parse(json: String): WakeWordModelManifest? {
        if (json.isEmpty() || json.length > MAX_MANIFEST_BYTES) return null
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MANIFEST_BYTES) return null
        return parseUtf8(bytes)
    }

    private fun parseUtf8(bytes: ByteArray): WakeWordModelManifest? {
        if (bytes.isEmpty() || bytes.size > MAX_MANIFEST_BYTES) return null
        val json = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            return null
        }
        return try {
            val root = JSONObject(json)
            if (root.keys().asSequence().toSet() != requiredKeys) return null

            val operations = root.optJSONArray("preprocessingOperations")
                ?.toStringList() ?: return null

            val manifest = WakeWordModelManifest(
                schemaVersion = root.intValue("schemaVersion") ?: return null,
                modelFileName = root.stringValue("modelFileName") ?: return null,
                modelSha256 = root.stringValue("modelSha256") ?: return null,
                modelBytes = root.longValue("modelBytes") ?: return null,
                wakePhrase = root.stringValue("wakePhrase") ?: return null,
                languageTag = root.stringValue("languageTag") ?: return null,
                sampleRateHz = root.intValue("sampleRateHz") ?: return null,
                channels = root.intValue("channels") ?: return null,
                audioWindowSamples = root.intValue("audioWindowSamples") ?: return null,
                fftSize = root.intValue("fftSize") ?: return null,
                windowSamples = root.intValue("windowSamples") ?: return null,
                hopSamples = root.intValue("hopSamples") ?: return null,
                centerPaddingSamples = root.intValue("centerPaddingSamples") ?: return null,
                melBins = root.intValue("melBins") ?: return null,
                frameCount = root.intValue("frameCount") ?: return null,
                tensorBatch = root.intValue("tensorBatch") ?: return null,
                tensorMelBins = root.intValue("tensorMelBins") ?: return null,
                tensorFrames = root.intValue("tensorFrames") ?: return null,
                preprocessingOperations = operations,
                preprocessingProfileId = root.stringValue("preprocessingProfileId") ?: return null,
                preprocessingProfileSha256 = root.stringValue("preprocessingProfileSha256") ?: return null,
                modelInputName = root.stringValue("modelInputName") ?: return null,
                modelOutputName = root.stringValue("modelOutputName") ?: return null,
                threshold = root.floatValue("threshold") ?: return null,
                consecutiveFrames = root.intValue("consecutiveFrames") ?: return null,
                refractoryMillis = root.intValue("refractoryMillis") ?: return null,
                licenseId = root.stringValue("licenseId") ?: return null,
                exporterVersion = root.stringValue("exporterVersion") ?: return null
            )
            if (WakeWordModelManifestPolicy.validate(manifest) == null) manifest else null
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.stringValue(key: String): String? = opt(key) as? String

    private fun JSONObject.intValue(key: String): Int? =
        opt(key).integralLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun JSONObject.longValue(key: String): Long? = opt(key).integralLong()

    private fun JSONObject.floatValue(key: String): Float? {
        val number = opt(key) as? Number ?: return null
        val value = number.toDouble()
        if (!value.isFinite() || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) return null
        return value.toFloat().takeIf { it.isFinite() }
    }

    private fun Any?.integralLong(): Long? {
        val number = this as? Number ?: return null
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 ||
            value < Long.MIN_VALUE.toDouble() || value >= Long.MAX_VALUE.toDouble()
        ) return null
        return number.toLong()
    }

    private fun JSONArray.toStringList(): List<String>? {
        if (length() !in 3..4) return null
        val result = ArrayList<String>(length())
        for (index in 0 until length()) {
            result += opt(index) as? String ?: return null
        }
        return result
    }
}

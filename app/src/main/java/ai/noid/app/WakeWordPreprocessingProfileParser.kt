package ai.noid.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Validates the scalar/runtime contract used by Heed's exported wake.json.
 * DSP coefficient tables are a separate artifact and are not claimed verified here.
 */
internal object WakeWordPreprocessingProfileParser {
    data class Profile(
        val phrase: String,
        val threshold: Float,
        val sampleRate: Int,
        val audioWindowSamples: Int,
        val melBins: Int,
        val fftSize: Int,
        val windowSamples: Int,
        val hopSamples: Int,
        val windowFrames: Int,
        val operations: List<String>,
        val consecutiveFrames: Int,
        val refractoryMillis: Int,
        val energyGateRmsDbfs: Float,
        val voiceBandMinFraction: Float,
        val voiceBandLowHz: Float,
        val voiceBandHighHz: Float
    )

    private val rootKeys = setOf(
        "phrase", "threshold", "sample_rate", "audio_window_samples", "n_mels",
        "n_fft", "win_length", "hop_length", "window_frames", "mel_fmin_hz",
        "mel_fmax_hz", "window_type", "preprocessing", "trigger", "energy_gate",
        "model_size", "channels", "n_blocks", "heed_export_version"
    )

    fun parse(json: String, manifest: WakeWordModelManifest): Profile? {
        if (json.isEmpty() || json.toByteArray(Charsets.UTF_8).size > 64 * 1024) return null
        return try {
            val root = JSONObject(json)
            if (root.keys().asSequence().toSet() != rootKeys) return null

            val phrase = root.string("phrase") ?: return null
            val threshold = root.float("threshold") ?: return null
            val sampleRate = root.int("sample_rate") ?: return null
            val audioWindow = root.int("audio_window_samples") ?: return null
            val melBins = root.int("n_mels") ?: return null
            val fft = root.int("n_fft") ?: return null
            val window = root.int("win_length") ?: return null
            val hop = root.int("hop_length") ?: return null
            val windowFrames = root.int("window_frames") ?: return null
            val melMin = root.float("mel_fmin_hz") ?: return null
            val melMax = root.float("mel_fmax_hz") ?: return null
            if (root.string("window_type") != "hann") return null

            val operations = parseOperations(root.optJSONArray("preprocessing") ?: return null)
                ?: return null
            val trigger = root.optJSONObject("trigger") ?: return null
            if (trigger.keys().asSequence().toSet() != setOf(
                    "consecutive_frames", "refractory_seconds", "ema_alpha", "note"
                )
            ) return null
            val consecutive = trigger.int("consecutive_frames") ?: return null
            val refractorySeconds = trigger.float("refractory_seconds") ?: return null
            val emaAlpha = trigger.float("ema_alpha") ?: return null
            if (trigger.string("note")?.length !in 1..500) return null

            val gate = root.optJSONObject("energy_gate") ?: return null
            if (gate.keys().asSequence().toSet() != setOf(
                    "rms_threshold_dbfs", "voice_band_min_fraction",
                    "voice_band_lo_hz", "voice_band_hi_hz", "note"
                )
            ) return null
            val rms = gate.float("rms_threshold_dbfs") ?: return null
            val voiceFraction = gate.float("voice_band_min_fraction") ?: return null
            val voiceLow = gate.float("voice_band_lo_hz") ?: return null
            val voiceHigh = gate.float("voice_band_hi_hz") ?: return null
            if (gate.string("note")?.length !in 1..500) return null

            val modelSize = root.string("model_size") ?: return null
            val channels = root.int("channels") ?: return null
            val blocks = root.int("n_blocks") ?: return null
            val exportVersion = root.int("heed_export_version") ?: return null

            if (phrase != manifest.wakePhrase || threshold != manifest.threshold ||
                sampleRate != manifest.sampleRateHz || audioWindow != manifest.audioWindowSamples ||
                melBins != manifest.melBins || fft != manifest.fftSize ||
                window != manifest.windowSamples || hop != manifest.hopSamples ||
                operations != manifest.preprocessingOperations ||
                consecutive != manifest.consecutiveFrames ||
                (refractorySeconds * 1000f).toInt() != manifest.refractoryMillis
            ) return null

            if (sampleRate !in 8_000..48_000 || audioWindow !in hop..96_000 ||
                melBins !in 1..128 || fft !in 128..4096 || fft.countOneBits() != 1 ||
                window !in 1..fft || hop !in 1..fft ||
                windowFrames != audioWindow / hop || audioWindow % hop != 0 ||
                manifest.frameCount != windowFrames + 1 ||
                melMin != 0f || melMax != sampleRate / 2f ||
                threshold !in 0f..1f || consecutive !in 1..20 ||
                !refractorySeconds.isFinite() || refractorySeconds !in 0f..10f ||
                !emaAlpha.isFinite() || emaAlpha !in 0f..1f ||
                !rms.isFinite() || rms !in -120f..0f ||
                !voiceFraction.isFinite() || voiceFraction !in 0f..1f ||
                !voiceLow.isFinite() || !voiceHigh.isFinite() ||
                voiceLow < 0f || voiceHigh <= voiceLow || voiceHigh > sampleRate / 2f ||
                modelSize !in setOf("small", "medium", "large") ||
                channels !in 1..512 || blocks !in 1..32 || exportVersion !in 1..100
            ) return null

            Profile(
                phrase, threshold, sampleRate, audioWindow, melBins, fft, window, hop,
                windowFrames, operations, consecutive, manifest.refractoryMillis,
                rms, voiceFraction, voiceLow, voiceHigh
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseOperations(array: JSONArray): List<String>? {
        if (array.length() !in 3..4) return null
        val operations = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: return null
            val op = item.string("op") ?: return null
            val keys = item.keys().asSequence().toSet()
            val validKeys = when (op) {
                "highpass_filter" -> setOf("op", "cutoff_hz", "order", "mains_notch_hz", "note")
                "peak_normalize" -> setOf("op", "target_dbfs", "note")
                "log_mel", "cmn" -> setOf("op", "note")
                else -> return null
            }
            if (keys != validKeys || item.string("note")?.length !in 1..500) return null
            when (op) {
                "highpass_filter" -> {
                    if (item.float("cutoff_hz") != 100f || item.int("order") != 8) return null
                    val notches = item.optJSONArray("mains_notch_hz") ?: return null
                    if (notches.length() != 2 || notches.opt(0) !is Number ||
                        notches.opt(1) !is Number ||
                        (notches.opt(0) as Number).toFloat() != 50f ||
                        (notches.opt(1) as Number).toFloat() != 60f
                    ) return null
                }
                "peak_normalize" -> {
                    val target = item.float("target_dbfs") ?: return null
                    if (target !in -12f..0f) return null
                }
            }
            operations += op
        }
        if (operations.firstOrNull() != "highpass_filter" ||
            operations.lastOrNull() != "cmn" ||
            operations.count { it == "log_mel" } != 1 ||
            operations.count { it == "highpass_filter" } != 1 ||
            operations.count { it == "cmn" } != 1 ||
            operations.count { it == "peak_normalize" } > 1
        ) return null
        val peakIndex = operations.indexOf("peak_normalize")
        val melIndex = operations.indexOf("log_mel")
        if (peakIndex >= 0 && peakIndex > melIndex) return null
        return operations
    }

    private fun JSONObject.string(key: String): String? = opt(key) as? String

    private fun JSONObject.int(key: String): Int? {
        val number = opt(key) as? Number ?: return null
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 ||
            value < Int.MIN_VALUE || value > Int.MAX_VALUE
        ) return null
        return value.toInt()
    }

    private fun JSONObject.float(key: String): Float? {
        val number = opt(key) as? Number ?: return null
        val value = number.toDouble()
        if (!value.isFinite() || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) return null
        return value.toFloat().takeIf { it.isFinite() }
    }
}

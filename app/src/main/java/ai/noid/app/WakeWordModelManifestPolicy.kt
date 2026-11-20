package ai.noid.app

/**
 * Fail-closed validation for a future NOID Arabic wake-word model bundle.
 * Values must originate from a parsed manifest; callers must separately verify
 * both SHA-256 digests against the actual model and preprocessing-profile bytes.
 */
internal object WakeWordModelManifestPolicy {
    const val MAX_MODEL_BYTES = 512L * 1024L * 1024L
    const val MAX_WINDOW_SAMPLES = 96_000
    const val MAX_MEL_BINS = 128
    const val MAX_FRAMES = 512
    const val MAX_REFRACTORY_MILLIS = 10_000

    private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")
    private val safeModelNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.(onnx|tflite)$")
    private val supportedOperations = setOf("highpass_filter", "peak_normalize", "log_mel", "cmn")

    fun validate(manifest: WakeWordModelManifest): String? {
        if (manifest.schemaVersion != 1) return "إصدار ملف تعريف النموذج غير مدعوم."
        if (!safeModelNamePattern.matches(manifest.modelFileName)) return "اسم ملف النموذج غير صالح."
        if (!sha256Pattern.matches(manifest.modelSha256)) return "بصمة النموذج غير صالحة."
        if (manifest.modelBytes !in 1..MAX_MODEL_BYTES) return "حجم نموذج اكتشاف الكلمة غير مقبول."
        if (manifest.wakePhrase.trim() != "يا NOID") return "عبارة التنبيه لا تطابق «يا NOID»."
        if (manifest.languageTag != "ar-IQ") return "لغة النموذج يجب أن تكون العربية العراقية ar-IQ."
        if (manifest.sampleRateHz !in 8_000..48_000 || manifest.channels != 1) {
            return "صيغة الصوت يجب أن تكون أحادية وبمعدل مدعوم."
        }
        if (manifest.audioWindowSamples !in 1..MAX_WINDOW_SAMPLES) return "حجم نافذة الصوت خارج الحدود."
        if (manifest.fftSize < 128 || manifest.fftSize > 4_096 ||
            manifest.fftSize.countOneBits() != 1
        ) return "حجم FFT يجب أن يكون قوة للعدد اثنين ضمن الحدود."
        if (manifest.windowSamples !in 1..manifest.fftSize ||
            manifest.hopSamples !in 1..manifest.fftSize
        ) return "إعدادات نافذة التحليل أو خطوة الإطار غير صالحة."
        if (manifest.centerPaddingSamples !in 0..manifest.fftSize) {
            return "قيمة الحشو المركزي غير صالحة."
        }
        if (manifest.melBins !in 1..MAX_MEL_BINS || manifest.frameCount !in 1..MAX_FRAMES) {
            return "أبعاد ميزات الصوت خارج الحدود."
        }
        val paddedSamples = manifest.audioWindowSamples.toLong() +
            2L * manifest.centerPaddingSamples.toLong()
        if (paddedSamples < manifest.fftSize) return "نافذة الصوت أقصر من إطار FFT."
        val numerator = paddedSamples - manifest.fftSize
        if (numerator % manifest.hopSamples.toLong() != 0L) {
            return "أبعاد الإطارات لا تتطابق مع إعدادات التحليل."
        }
        val expectedFrames = numerator / manifest.hopSamples.toLong() + 1L
        if (expectedFrames != manifest.frameCount.toLong()) {
            return "عدد إطارات النموذج لا يطابق إعدادات التحليل."
        }
        if (manifest.tensorBatch != 1 || manifest.tensorMelBins != manifest.melBins ||
            manifest.tensorFrames != manifest.frameCount
        ) return "شكل مدخل النموذج لا يطابق أبعاد الميزات."
        if (!validOperations(manifest.preprocessingOperations)) {
            return "خطوات المعالجة المسبقة غير مدعومة أو بترتيب غير صالح."
        }
        if (manifest.preprocessingProfileId.isBlank() || manifest.preprocessingProfileId.length > 100 ||
            !sha256Pattern.matches(manifest.preprocessingProfileSha256)
        ) return "ملف معاملات المعالجة المسبقة أو بصمته غير صالح."
        if (!validTensorName(manifest.modelInputName) || !validTensorName(manifest.modelOutputName) ||
            manifest.modelInputName == manifest.modelOutputName
        ) return "أسماء موترات النموذج غير صالحة."
        if (!manifest.threshold.isFinite() || manifest.threshold !in 0f..1f) {
            return "عتبة اكتشاف الكلمة خارج النطاق."
        }
        if (manifest.consecutiveFrames !in 1..20 ||
            manifest.refractoryMillis !in 0..MAX_REFRACTORY_MILLIS
        ) return "إعدادات تأكيد التنبيه غير صالحة."
        if (manifest.licenseId.isBlank() || manifest.licenseId.length > 80 ||
            manifest.exporterVersion.isBlank() || manifest.exporterVersion.length > 80
        ) return "بيانات الترخيص أو أداة التصدير ناقصة."
        return null
    }

    private fun validOperations(operations: List<String>): Boolean {
        if (operations.size !in 3..4 || operations.any { it !in supportedOperations }) return false
        if (operations.distinct().size != operations.size) return false
        val logMel = operations.indexOf("log_mel")
        val cmn = operations.indexOf("cmn")
        if (operations.firstOrNull() != "highpass_filter" || logMel < 1 || cmn != operations.lastIndex) {
            return false
        }
        val peakNormalize = operations.indexOf("peak_normalize")
        return peakNormalize == -1 || peakNormalize in 1 until logMel
    }

    private fun validTensorName(value: String): Boolean =
        value.length in 1..120 && value.all { it.isLetterOrDigit() || it == '_' || it == '.' }
}

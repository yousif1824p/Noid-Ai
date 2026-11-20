package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordModelManifestPolicyTest {
    private fun validManifest() = WakeWordModelManifest(
        schemaVersion = 1, modelFileName = "noid-ar-wake.onnx",
        modelSha256 = "a".repeat(64), modelBytes = 12_000_000,
        wakePhrase = "يا NOID", languageTag = "ar-IQ", sampleRateHz = 16_000, channels = 1,
        audioWindowSamples = 16_000, fftSize = 512, windowSamples = 400, hopSamples = 160,
        centerPaddingSamples = 256, melBins = 40, frameCount = 101,
        tensorBatch = 1, tensorMelBins = 40, tensorFrames = 101,
        preprocessingOperations = listOf("highpass_filter", "log_mel", "cmn"),
        preprocessingProfileId = "heed-compatible-profile-v1",
        preprocessingProfileSha256 = "b".repeat(64),
        modelInputName = "audio_features", modelOutputName = "wake_logit",
        threshold = 0.82f, consecutiveFrames = 2, refractoryMillis = 700,
        licenseId = "Apache-2.0", exporterVersion = "1.0"
    )

    @Test fun acceptsConsistentIraqiArabicManifest() {
        assertNull(WakeWordModelManifestPolicy.validate(validManifest()))
    }

    @Test fun rejectsEnglishDemoPhraseAndLocale() {
        assertEquals("عبارة التنبيه لا تطابق «يا NOID».",
            WakeWordModelManifestPolicy.validate(validManifest().copy(wakePhrase = "hey doc")))
        assertEquals("لغة النموذج يجب أن تكون العربية العراقية ar-IQ.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(languageTag = "en-US")))
    }

    @Test fun rejectsMissingOrMalformedModelIdentity() {
        assertEquals("اسم ملف النموذج غير صالح.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(modelFileName = "../wake.onnx")))
        assertEquals("بصمة النموذج غير صالحة.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(modelSha256 = "abc")))
        assertEquals("حجم نموذج اكتشاف الكلمة غير مقبول.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(modelBytes = 0)))
        assertEquals("إصدار ملف تعريف النموذج غير مدعوم.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(schemaVersion = 2)))
    }

    @Test fun rejectsTensorDimensionsThatDoNotMatchMetadata() {
        assertEquals("عدد إطارات النموذج لا يطابق إعدادات التحليل.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(frameCount = 100, tensorFrames = 100)))
        assertEquals("شكل مدخل النموذج لا يطابق أبعاد الميزات.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(tensorFrames = 100)))
        assertEquals("أبعاد الإطارات لا تتطابق مع إعدادات التحليل.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(hopSamples = 159)))
    }

    @Test fun rejectsUnsupportedOrAmbiguousPreprocessing() {
        val invalid = listOf(
            listOf("highpass_filter", "mystery_filter", "log_mel", "cmn"),
            listOf("highpass_filter", "log_mel", "log_mel", "cmn"),
            listOf("cmn", "highpass_filter", "log_mel")
        )
        invalid.forEach { operations ->
            assertEquals("خطوات المعالجة المسبقة غير مدعومة أو بترتيب غير صالح.",
                WakeWordModelManifestPolicy.validate(validManifest().copy(preprocessingOperations = operations)))
        }
    }

    @Test fun rejectsInvalidTriggerAndTensorNames() {
        assertEquals("عتبة اكتشاف الكلمة خارج النطاق.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(threshold = Float.NaN)))
        assertEquals("إعدادات تأكيد التنبيه غير صالحة.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(consecutiveFrames = 0)))
        assertEquals("أسماء موترات النموذج غير صالحة.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(modelInputName = "../input")))
    }

    @Test fun rejectsMissingPreprocessingProfileDigest() {
        assertEquals("ملف معاملات المعالجة المسبقة أو بصمته غير صالح.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(preprocessingProfileSha256 = " ")))
    }

    @Test fun acceptsOptionalPeakNormalizationOnlyBeforeLogMel() {
        assertNull(WakeWordModelManifestPolicy.validate(validManifest().copy(
            preprocessingOperations = listOf("highpass_filter", "peak_normalize", "log_mel", "cmn")
        )))
        assertEquals("خطوات المعالجة المسبقة غير مدعومة أو بترتيب غير صالح.",
            WakeWordModelManifestPolicy.validate(validManifest().copy(
                preprocessingOperations = listOf("highpass_filter", "log_mel", "peak_normalize", "cmn")
            )))
    }
}

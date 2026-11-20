package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeywordSpottingBoundaryPolicyTest {
    private val descriptor = KeywordModelDescriptor(
        modelId = "ya-noid-ar",
        displayName = "Ya NOID Arabic",
        languageTag = "ar-IQ",
        wakePhrase = "يا NOID",
        sampleRateHz = 16_000,
        frameLengthSamples = 320,
        localOnly = true,
        modelVersion = "1"
    )

    @Test
    fun acceptsExactConfiguredFrameLength() {
        assertNull(KeywordSpottingBoundaryPolicy.validateAudioFrame(ShortArray(320), descriptor))
    }

    @Test
    fun rejectsWrongFrameLengthAndInvalidDescriptor() {
        assertEquals(
            "طول إطار الصوت لا يطابق إعدادات النموذج.",
            KeywordSpottingBoundaryPolicy.validateAudioFrame(ShortArray(319), descriptor)
        )
        assertEquals(
            "بيانات نموذج اكتشاف الكلمة غير صالحة.",
            KeywordSpottingBoundaryPolicy.validateAudioFrame(
                ShortArray(320),
                descriptor.copy(localOnly = false)
            )
        )
    }

    @Test
    fun acceptsMatchingWakePhraseAndBoundedConfidence() {
        assertNull(
            KeywordSpottingBoundaryPolicy.validateEvent(
                KeywordSpottingEvent.Detected("يا NOID", 0.95f),
                descriptor
            )
        )
        assertNull(
            KeywordSpottingBoundaryPolicy.validateEvent(
                KeywordSpottingEvent.Detected("يا NOID", 0f),
                descriptor
            )
        )
        assertNull(
            KeywordSpottingBoundaryPolicy.validateEvent(
                KeywordSpottingEvent.Detected("يا NOID", 1f),
                descriptor
            )
        )
    }

    @Test
    fun rejectsEmptyOrDifferentDetectedPhrase() {
        assertEquals(
            "عبارة التنبيه المكتشفة فارغة.",
            KeywordSpottingBoundaryPolicy.validateEvent(
                KeywordSpottingEvent.Detected("  ", 0.8f),
                descriptor
            )
        )
        assertEquals(
            "عبارة التنبيه المكتشفة لا تطابق العبارة المضبوطة.",
            KeywordSpottingBoundaryPolicy.validateEvent(
                KeywordSpottingEvent.Detected("يا مساعد", 0.8f),
                descriptor
            )
        )
    }

    @Test
    fun rejectsNonFiniteAndOutOfRangeConfidence() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f, 1.01f).forEach { confidence ->
            assertEquals(
                "درجة الثقة خارج النطاق المسموح.",
                KeywordSpottingBoundaryPolicy.validateEvent(
                    KeywordSpottingEvent.Detected("يا NOID", confidence),
                    descriptor
                )
            )
        }
    }

    @Test
    fun validatesUnavailableReason() {
        assertNull(
            KeywordSpottingBoundaryPolicy.validateEvent(
                KeywordSpottingEvent.Unavailable("لم يتم تحميل النموذج"),
                descriptor
            )
        )
        assertEquals(
            "سبب عدم توفر النموذج فارغ.",
            KeywordSpottingBoundaryPolicy.validateEvent(
                KeywordSpottingEvent.Unavailable(" "),
                descriptor
            )
        )
        assertEquals(
            "سبب عدم توفر النموذج أطول من الحد المسموح.",
            KeywordSpottingBoundaryPolicy.validateEvent(
                KeywordSpottingEvent.Unavailable(
                    "x".repeat(KeywordSpottingBoundaryPolicy.MAX_UNAVAILABLE_REASON_LENGTH + 1)
                ),
                descriptor
            )
        )
    }

    @Test
    fun rejectsDescriptorWithInvalidRateFrameOrMissingIdentity() {
        listOf(
            descriptor.copy(modelId = " "),
            descriptor.copy(wakePhrase = ""),
            descriptor.copy(sampleRateHz = 7_999),
            descriptor.copy(sampleRateHz = 48_001),
            descriptor.copy(frameLengthSamples = 79),
            descriptor.copy(frameLengthSamples = 4_801),
            descriptor.copy(modelVersion = " ")
        ).forEach { invalid ->
            assertEquals(
                "بيانات نموذج اكتشاف الكلمة غير صالحة.",
                KeywordSpottingBoundaryPolicy.validateAudioFrame(ShortArray(320), invalid)
            )
        }
    }

    @Test
    fun rejectsDetectedEventWhenDescriptorIsNotLocal() {
        assertEquals(
            "بيانات نموذج اكتشاف الكلمة غير صالحة.",
            KeywordSpottingBoundaryPolicy.validateEvent(
                KeywordSpottingEvent.Detected("يا NOID", 0.9f),
                descriptor.copy(localOnly = false)
            )
        )
    }
}

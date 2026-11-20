package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class VisionInputPolicyTest {
    private fun request(
        uri: String = "content://media/picker/1",
        mime: String = "image/jpeg",
        bytes: Long = 1024,
        prompt: String = "Describe this image"
    ) = VisionAnalysisRequest(
        prompt = prompt,
        image = VisionImageInput(uri, mime, bytes)
    )

    @Test
    fun acceptsSupportedUserSelectedImage() {
        assertNull(VisionInputPolicy.validate(request()))
    }

    @Test
    fun rejectsFilePathsAndNonContentUris() {
        assertEquals(
            "يجب اختيار الصورة من منتقي ملفات Android.",
            VisionInputPolicy.validate(request(uri = "/sdcard/private/image.jpg"))
        )
    }

    @Test
    fun rejectsUnsupportedMimeTypes() {
        assertEquals(
            "صيغة الصورة غير مدعومة. الصيغ المقبولة: JPEG وPNG وWebP.",
            VisionInputPolicy.validate(request(mime = "image/gif"))
        )
    }

    @Test
    fun rejectsEmptyAndOversizedImages() {
        assertEquals(
            "حجم الصورة يجب أن يكون أكبر من صفر ولا يتجاوز 12 MiB.",
            VisionInputPolicy.validate(request(bytes = 0))
        )
        assertEquals(
            "حجم الصورة يجب أن يكون أكبر من صفر ولا يتجاوز 12 MiB.",
            VisionInputPolicy.validate(request(bytes = VisionInputPolicy.MAX_IMAGE_BYTES + 1))
        )
    }

    @Test
    fun rejectsOversizedPrompt() {
        assertEquals(
            "وصف التحليل يتجاوز الحد الأقصى البالغ 2000 حرف.",
            VisionInputPolicy.validate(request(prompt = "x".repeat(2_001)))
        )
    }

    @Test
    fun acceptsAllSupportedImageTypesCaseInsensitively() {
        listOf("image/jpeg", "image/png", "image/webp", "IMAGE/PNG").forEach { mime ->
            assertNull(VisionInputPolicy.validate(request(mime = mime)))
        }
    }

    @Test
    fun mimeNormalizationIsIndependentOfTurkishDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertNull(VisionInputPolicy.validate(request(mime = "IMAGE/PNG")))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun acceptsExactMaximumImageSize() {
        assertNull(
            VisionInputPolicy.validate(
                request(bytes = VisionInputPolicy.MAX_IMAGE_BYTES)
            )
        )
    }
}

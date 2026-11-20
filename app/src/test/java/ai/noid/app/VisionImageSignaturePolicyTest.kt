package ai.noid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VisionImageSignaturePolicyTest {
    @Test
    fun acceptsJpegSignature() {
        assertTrue(
            VisionImageSignaturePolicy.matches(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00),
                "image/jpeg"
            )
        )
    }

    @Test
    fun acceptsPngSignature() {
        assertTrue(
            VisionImageSignaturePolicy.matches(
                byteArrayOf(
                    0x89.toByte(), 0x50, 0x4E, 0x47,
                    0x0D, 0x0A, 0x1A, 0x0A, 0x00
                ),
                "image/png"
            )
        )
    }

    @Test
    fun acceptsWebpContainerSignature() {
        val header = "RIFF0000WEBP".encodeToByteArray()
        assertTrue(VisionImageSignaturePolicy.matches(header, "image/webp"))
    }

    @Test
    fun rejectsMimeAndSignatureMismatch() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        assertFalse(VisionImageSignaturePolicy.matches(jpeg, "image/png"))
        assertFalse(VisionImageSignaturePolicy.matches(jpeg, "image/webp"))
    }

    @Test
    fun mimeNormalizationIsIndependentOfTurkishDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(
                VisionImageSignaturePolicy.matches(
                    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
                    "IMAGE/JPEG"
                )
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun rejectsTruncatedAndUnknownContent() {
        assertFalse(VisionImageSignaturePolicy.matches(byteArrayOf(0xFF.toByte()), "image/jpeg"))
        assertFalse(VisionImageSignaturePolicy.matches(byteArrayOf(), "image/png"))
        assertFalse(VisionImageSignaturePolicy.matches("GIF89a".encodeToByteArray(), "image/gif"))
    }
}

package ai.noid.app

import java.util.Locale

/**
 * Checks container signatures against the MIME type returned by ContentResolver.
 * This is an inexpensive format-consistency check, not a full image decoder.
 */
internal object VisionImageSignaturePolicy {
    fun matches(bytes: ByteArray, mimeType: String): Boolean = when (mimeType.lowercase(Locale.ROOT)) {
        "image/jpeg" -> bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

        "image/png" -> bytes.size >= PNG_SIGNATURE.size &&
            bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)

        "image/webp" -> bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"

        else -> false
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
}

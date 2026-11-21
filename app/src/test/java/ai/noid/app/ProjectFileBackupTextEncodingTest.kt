package ai.noid.app

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectFileBackupTextEncodingTest {
    @Test fun decodesAsciiWithoutChangingBytes() {
        assertEquals("plain source", ProjectFileBackupTextEncoding.decode("plain source".toByteArray()))
    }

    @Test fun decodesArabicAndMixedScripts() {
        val source = "نويد — NOID — مرحباً"
        assertEquals(source, ProjectFileBackupTextEncoding.decode(source.toByteArray(Charsets.UTF_8)))
    }

    @Test fun decodesFourByteUnicodeCharacters() {
        val source = "A😀𐐷Z"
        assertEquals(source, ProjectFileBackupTextEncoding.decode(source.toByteArray(Charsets.UTF_8)))
    }

    @Test fun acceptsEmptyBackup() {
        assertEquals("", ProjectFileBackupTextEncoding.decode(byteArrayOf()))
    }

    @Test fun rejectsLoneContinuationByte() {
        assertThrows(IOException::class.java) {
            ProjectFileBackupTextEncoding.decode(byteArrayOf(0x80.toByte()))
        }
    }

    @Test fun rejectsTruncatedTwoByteSequence() {
        assertThrows(IOException::class.java) {
            ProjectFileBackupTextEncoding.decode(byteArrayOf(0xC3.toByte()))
        }
    }

    @Test fun rejectsTruncatedThreeByteSequence() {
        assertThrows(IOException::class.java) {
            ProjectFileBackupTextEncoding.decode(byteArrayOf(0xE2.toByte(), 0x82.toByte()))
        }
    }

    @Test fun rejectsTruncatedFourByteSequence() {
        assertThrows(IOException::class.java) {
            ProjectFileBackupTextEncoding.decode(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x92.toByte()))
        }
    }

    @Test fun rejectsOverlongUtf8Encoding() {
        assertThrows(IOException::class.java) {
            ProjectFileBackupTextEncoding.decode(byteArrayOf(0xC0.toByte(), 0xAF.toByte()))
        }
    }

    @Test fun rejectsSurrogatesOutOfRangeAndMalformedInteriorBytes() {
        val invalid = listOf(
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0x61, 0x62, 0xFF.toByte(), 0x63)
        )
        invalid.forEach { bytes ->
            assertThrows(IOException::class.java) {
                ProjectFileBackupTextEncoding.decode(bytes)
            }
        }
    }
}

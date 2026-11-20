package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModelFilePolicyTest {
    @Test
    fun advancesTimestampWhenPreviousModelNameAlreadyExists() {
        assertEquals(100L, VisionModelFilePolicy.nextAvailableTimestamp(100L) { false })
        assertEquals(102L, VisionModelFilePolicy.nextAvailableTimestamp(100L) { it < 102L })
    }

    @Test
    fun rejectsInvalidOrExhaustedTimestampSequence() {
        assertNull(VisionModelFilePolicy.nextAvailableTimestamp(0L) { false })
        assertNull(VisionModelFilePolicy.nextAvailableTimestamp(-1L) { false })
        assertNull(VisionModelFilePolicy.nextAvailableTimestamp(Long.MAX_VALUE) { true })
    }

    @Test
    fun acceptsPositiveDecimalTimestampWithExpectedExtension() {
        assertTrue(VisionModelFilePolicy.isSafeStoredFileName("vision-model-1.litertlm"))
        assertTrue(VisionModelFilePolicy.isSafeStoredFileName("vision-model-1763320000000.litertlm"))
    }

    @Test
    fun rejectsNullBlankAndMissingTimestamp() {
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName(null))
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName(""))
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("   "))
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("vision-model-.litertlm"))
    }

    @Test
    fun rejectsZeroNegativeAndSignedTimestamps() {
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("vision-model-0.litertlm"))
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("vision-model--1.litertlm"))
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("vision-model-+1.litertlm"))
    }

    @Test
    fun rejectsNonDecimalAndOverflowTimestamps() {
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("vision-model-1a.litertlm"))
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("vision-model-999999999999999999999999999.litertlm"))
    }

    @Test
    fun rejectsTraversalSeparatorsAndUnexpectedExtensions() {
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("vision-model-1/other.litertlm"))
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("vision-model-1\\\\other.litertlm"))
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("../vision-model-1.litertlm"))
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("vision-model-1.apk"))
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("Vision-model-1.litertlm"))
    }

    @Test
    fun rejectsControlCharactersAndOverlongNames() {
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("vision-model-1\n.litertlm"))
        assertFalse(VisionModelFilePolicy.isSafeStoredFileName("vision-model-" + "1".repeat(90) + ".litertlm"))
    }
}

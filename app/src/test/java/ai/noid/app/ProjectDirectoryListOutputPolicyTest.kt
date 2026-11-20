package ai.noid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDirectoryListOutputPolicyTest {
    @Test
    fun outputLimitNeverCutsAnAcceptedEntryInHalf() {
        val entries = listOf(
            ProjectFileEntry("alpha.kt", "alpha.kt", false, 5),
            ProjectFileEntry("b".repeat(180), "b", false, 12)
        )

        val result = ProjectDirectoryListOutputPolicy.format(entries, maxOutputChars = 64)

        assertTrue(result.startsWith("FILE\talpha.kt\t5 bytes"))
        assertTrue(result.endsWith("… تم إخفاء 1 عنصراً إضافياً."))
        assertFalse(result.contains("b".repeat(30)))
        assertTrue(result.length <= 64)
    }

    @Test
    fun namesAreSanitizedAndSizesCannotBecomeNegative() {
        val entry = ProjectFileEntry("bad\n\u202Ename.kt", "bad", false, -9)

        val result = ProjectDirectoryListOutputPolicy.format(listOf(entry))

        assertTrue(result.contains("bad  name.kt"))
        assertTrue(result.endsWith("0 bytes"))
        assertFalse(result.contains("\u202E"))
        assertFalse(result.contains("\nname"))
    }

    @Test
    fun entryCountAndEmptyDirectoryAreBounded() {
        val entries = (1..4).map { ProjectFileEntry("file$it.kt", "file$it.kt", false, it.toLong()) }
        val result = ProjectDirectoryListOutputPolicy.format(entries, maxEntries = 2)

        assertTrue(result.contains("file1.kt"))
        assertTrue(result.contains("file2.kt"))
        assertFalse(result.contains("file3.kt"))
        assertTrue(result.endsWith("… تم إخفاء 2 عنصراً إضافياً."))
        org.junit.Assert.assertEquals("المجلد فارغ.", ProjectDirectoryListOutputPolicy.format(emptyList()))
    }
}

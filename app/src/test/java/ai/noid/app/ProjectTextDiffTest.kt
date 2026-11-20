package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTextDiffTest {
    @Test fun reportsAddedAndRemovedLines() {
        val result = ProjectTextDiff.compare("one\ntwo\nthree", "one\nchanged\nthree\nfour")
        assertEquals(2, result.additions)
        assertEquals(1, result.removals)
        assertFalse(result.truncated)
        assertTrue(result.lines.any { it.kind == ProjectDiffKind.ADDED && it.text == "changed" })
        assertTrue(result.lines.any { it.kind == ProjectDiffKind.REMOVED && it.text == "two" })
    }

    @Test fun unchangedTextHasNoChanges() {
        val result = ProjectTextDiff.compare("same\ntext", "same\ntext")
        assertEquals(0, result.additions)
        assertEquals(0, result.removals)
        assertTrue(result.lines.all { it.kind == ProjectDiffKind.UNCHANGED })
    }

    @Test fun largeDiffIsBounded() {
        val before = (1..ProjectTextDiff.MAX_LINES_PER_SIDE + 1).joinToString("\n") { "line-$it" }
        val result = ProjectTextDiff.compare(before, before + "\nnew")
        assertTrue(result.truncated)
        assertTrue(result.lines.isEmpty())
    }
}

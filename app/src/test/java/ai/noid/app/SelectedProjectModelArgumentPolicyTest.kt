package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedProjectModelArgumentPolicyTest {
    private fun bind(arguments: Map<String, Any?>, selectionId: String? = "active") =
        SelectedProjectModelArgumentPolicy.bindPath(
            arguments = arguments,
            activeSelectionId = selectionId,
            pathArgument = "path",
            selectionArgument = "selectionId"
        )

    @Test
    fun bindsOnlyPathToTheCurrentAppOwnedSelection() {
        assertEquals(
            mapOf("selectionId" to "active", "path" to "src/Main.kt"),
            bind(mapOf("path" to "src/Main.kt"))
        )
    }

    @Test
    fun allowsEmptyPathForTheSelectedProjectRoot() {
        assertEquals(
            mapOf("selectionId" to "active", "path" to ""),
            bind(mapOf("path" to ""))
        )
    }

    @Test
    fun rejectsMissingOrBlankActiveSelection() {
        assertNull(bind(mapOf("path" to "src/Main.kt"), null))
        assertNull(bind(mapOf("path" to "src/Main.kt"), "  "))
    }

    @Test
    fun rejectsModelSuppliedSelectionTokensUrisAndUnknownArguments() {
        assertNull(bind(mapOf("path" to "src/Main.kt", "selectionId" to "attacker-choice")))
        assertNull(bind(mapOf("path" to "src/Main.kt", "uri" to "content://forged")))
        assertNull(bind(mapOf("path" to "src/Main.kt", "extra" to "value")))
    }

    @Test
    fun rejectsMissingOrNonStringPath() {
        assertNull(bind(emptyMap()))
        assertNull(bind(mapOf("path" to 42)))
        assertNull(bind(mapOf("path" to null)))
    }

    @Test
    fun rejectsInvalidArgumentNames() {
        assertNull(
            SelectedProjectModelArgumentPolicy.bindPath(
                mapOf("path" to "x"),
                "active",
                pathArgument = "",
                selectionArgument = "selectionId"
            )
        )
        assertNull(
            SelectedProjectModelArgumentPolicy.bindPath(
                mapOf("path" to "x"),
                "active",
                pathArgument = "path",
                selectionArgument = "path"
            )
        )
    }
}

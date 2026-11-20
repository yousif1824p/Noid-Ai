package ai.noid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFileImportPolicyTest {
    @Test
    fun acceptsLitertLmFilesCaseInsensitively() {
        assertTrue(ModelFileImportPolicy.isSupportedName("Gemma-1B.litertlm"))
        assertTrue(ModelFileImportPolicy.isSupportedName("model.LITERTLM"))
    }

    @Test
    fun rejectsWrongOrMalformedNames() {
        listOf(
            null,
            "",
            "model.gguf",
            ".litertlm",
            "../model.litertlm",
            "folder\\model.litertlm",
            "model\n.litertlm"
        ).forEach { name ->
            assertFalse(ModelFileImportPolicy.isSupportedName(name))
        }
    }

    @Test
    fun onlyRestoresGeneratedModelBasenames() {
        assertTrue(ModelFileImportPolicy.isSafeStoredFileName("model-123456.litertlm"))
        listOf(
            null, "", "../model-123456.litertlm", "folder/model-123456.litertlm",
            "model-latest.litertlm", "model-123456.gguf", "other-123456.litertlm"
        ).forEach { name ->
            assertFalse(ModelFileImportPolicy.isSafeStoredFileName(name))
        }
    }

    @Test
    fun boundsModelFileSize() {
        assertTrue(ModelFileImportPolicy.isSupportedSize(1))
        assertTrue(ModelFileImportPolicy.isSupportedSize(ModelFileImportPolicy.MAX_MODEL_BYTES))
        assertFalse(ModelFileImportPolicy.isSupportedSize(0))
        assertFalse(ModelFileImportPolicy.isSupportedSize(ModelFileImportPolicy.MAX_MODEL_BYTES + 1))
    }

    @Test
    fun checksModelAndReserveWithoutOverflow() {
        assertTrue(ModelFileImportPolicy.hasSufficientSpace(512, 1_024, 512))
        assertFalse(ModelFileImportPolicy.hasSufficientSpace(513, 1_024, 512))
        assertTrue(ModelFileImportPolicy.hasSufficientSpace(null, 512, 512))
        assertFalse(ModelFileImportPolicy.hasSufficientSpace(null, 511, 512))
        assertFalse(ModelFileImportPolicy.hasSufficientSpace(-1, Long.MAX_VALUE, 0))
        assertFalse(ModelFileImportPolicy.hasSufficientSpace(1, Long.MAX_VALUE, -1))
    }

    @Test
    fun copyBudgetProtectsReserveAndMaximumWithoutOverflow() {
        assertTrue(ModelFileImportPolicy.canCopyWithinBudget(400, 100, 1_000, 500))
        assertFalse(ModelFileImportPolicy.canCopyWithinBudget(400, 101, 1_000, 500))
        assertFalse(ModelFileImportPolicy.canCopyWithinBudget(Long.MAX_VALUE, 1, Long.MAX_VALUE, 0))
        assertFalse(ModelFileImportPolicy.canCopyWithinBudget(0, -1, 1_000, 0))
    }
}

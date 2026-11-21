package ai.noid.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFileIntegrityTest {
    @Test
    fun computesKnownSha256ForImportedModelBytes() {
        val file = File.createTempFile("noid-model-integrity", ".litertlm")
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ModelFileIntegrity.sha256(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun validatesSha256EncodingWithoutAcceptingWrongLengthsOrCharacters() {
        assertTrue(ModelFileIntegrity.isValidSha256("a".repeat(64)))
        assertTrue(ModelFileIntegrity.isValidSha256("A".repeat(64)))
        assertFalse(ModelFileIntegrity.isValidSha256("a".repeat(63)))
        assertFalse(ModelFileIntegrity.isValidSha256("g".repeat(64)))
        assertFalse(ModelFileIntegrity.isValidSha256(""))
    }

    @Test
    fun changedFileProducesDifferentFingerprint() {
        val file = File.createTempFile("noid-model-integrity", ".litertlm")
        try {
            file.writeText("model-a")
            val original = ModelFileIntegrity.sha256(file)
            file.writeText("model-b")
            assertFalse(original.equals(ModelFileIntegrity.sha256(file), ignoreCase = true))
        } finally {
            file.delete()
        }
    }
}

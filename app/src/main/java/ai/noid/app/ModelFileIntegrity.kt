package ai.noid.app

import java.io.File
import java.security.MessageDigest

/** Computes a local integrity fingerprint; it does not authenticate a model's publisher. */
object ModelFileIntegrity {
    private const val BUFFER_BYTES = 64 * 1024

    fun isValidSha256(value: String): Boolean =
        value.matches(Regex("[a-fA-F0-9]{64}"))

    fun sha256(file: File): String {
        require(file.isFile) { "Model file is not a regular file." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

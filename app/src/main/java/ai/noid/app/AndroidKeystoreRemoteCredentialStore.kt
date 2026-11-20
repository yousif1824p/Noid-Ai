package ai.noid.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App-private bearer-token storage backed by an Android Keystore AES-256 key.
 *
 * The token is encrypted before it reaches SharedPreferences. It is never
 * included in endpoint URLs, chat payloads, logs, or backup data. This class
 * is a storage primitive only; a UI must still require explicit user consent
 * before saving or using a credential.
 */
class AndroidKeystoreRemoteCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun saveBearerToken(token: String) {
        RemoteBearerTokenPolicy.validate(token)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val encoded = listOf(
            FORMAT_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        ).joinToString(".")
        check(preferences.edit().putString(TOKEN_KEY, encoded).commit()) {
            "Unable to persist the encrypted remote credential."
        }
    }

    @Synchronized
    fun readBearerToken(): String? {
        val stored = preferences.getString(TOKEN_KEY, null) ?: return null
        val parts = stored.split('.')
        check(parts.size == 3 && parts[0] == FORMAT_VERSION) {
            "Stored remote credential has an unsupported format."
        }
        val iv = decodePart(parts[1])
        val ciphertext = decodePart(parts[2])
        check(iv.size == GCM_IV_BYTES && ciphertext.isNotEmpty()) {
            "Stored remote credential is incomplete."
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        val token = plaintext.toString(Charsets.UTF_8)
        RemoteBearerTokenPolicy.validate(token)
        return token
    }

    @Synchronized
    fun clearBearerToken() {
        check(preferences.edit().remove(TOKEN_KEY).commit()) {
            "Unable to clear the stored remote credential."
        }
    }

    private fun decodePart(value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException("Stored remote credential is malformed.", failure)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "noid_remote_credentials"
        private const val TOKEN_KEY = "remote_bearer_token"
        private const val KEY_ALIAS = "noid_remote_bearer_aes_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = "v1"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}

/** Strict bounded token syntax; rejects whitespace, control characters, and header injection. */
object RemoteBearerTokenPolicy {
    const val MAX_TOKEN_CHARS = 4_096
    private val TOKEN_PATTERN = Regex("[A-Za-z0-9._~+/=-]{1,$MAX_TOKEN_CHARS}")

    fun validate(token: String) {
        require(token.isNotEmpty() && token.length <= MAX_TOKEN_CHARS) {
            "Remote bearer token is empty or exceeds the size limit."
        }
        require(TOKEN_PATTERN.matches(token)) {
            "Remote bearer token contains unsupported characters."
        }
    }
}

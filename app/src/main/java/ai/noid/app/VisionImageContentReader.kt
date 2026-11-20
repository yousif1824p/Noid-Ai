package ai.noid.app

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Reads only a user-granted content URI and enforces the byte limit while
 * streaming. Metadata supplied by a caller is never trusted as the size check.
 */
class VisionImageContentReader(
    private val contentResolver: ContentResolver
) {
    suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
            "Only user-selected content URIs are accepted."
        }
        val mimeType = contentResolver.getType(uri)?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("The image MIME type is unknown.")
        require(mimeType in SUPPORTED_MIME_TYPES) {
            "Unsupported image type."
        }

        val accumulator = BoundedImageByteAccumulator(
            maxBytes = VisionInputPolicy.MAX_IMAGE_BYTES.toInt(),
            initialCapacity = BUFFER_SIZE
        )
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    accumulator.append(buffer, 0, count)
                }
            } ?: throw IllegalArgumentException("The selected image cannot be opened.")

            require(accumulator.size > 0) { "The selected image is empty." }
            val bytes = accumulator.toByteArray()
            try {
                require(VisionImageSignaturePolicy.matches(bytes, mimeType)) {
                    "The image content does not match its declared MIME type."
                }
                val bounds = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                VisionImageDimensionsPolicy.validate(bounds.outWidth, bounds.outHeight)?.let { reason ->
                    throw IllegalArgumentException(reason)
                }
                bytes
            } catch (failure: Throwable) {
                bytes.fill(0)
                throw failure
            }
        } finally {
            buffer.fill(0)
            accumulator.clear()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

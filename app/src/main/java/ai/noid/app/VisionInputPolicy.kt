package ai.noid.app

import java.util.Locale

/**
 * Deterministic validation before an image is handed to a vision runtime.
 * This does not decode the image or claim that a vision model is installed.
 */
object VisionInputPolicy {
    const val MAX_IMAGE_BYTES = 12L * 1024L * 1024L
    const val MAX_PROMPT_LENGTH = 2_000

    private val supportedMimeTypes = setOf(
        "image/jpeg",
        "image/png",
        "image/webp"
    )

    fun validate(request: VisionAnalysisRequest): String? {
        val uri = request.image.contentUri.trim()
        if (!uri.startsWith("content://")) {
            return "يجب اختيار الصورة من منتقي ملفات Android."
        }
        if (request.image.mimeType.trim().lowercase(Locale.ROOT) !in supportedMimeTypes) {
            return "صيغة الصورة غير مدعومة. الصيغ المقبولة: JPEG وPNG وWebP."
        }
        request.image.byteCount?.let { byteCount ->
            if (byteCount <= 0L || byteCount > MAX_IMAGE_BYTES) {
                return "حجم الصورة يجب أن يكون أكبر من صفر ولا يتجاوز 12 MiB."
            }
        }
        if (request.prompt.length > MAX_PROMPT_LENGTH) {
            return "وصف التحليل يتجاوز الحد الأقصى البالغ 2000 حرف."
        }
        return null
    }
}

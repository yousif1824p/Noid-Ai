package ai.noid.app

/**
 * Limits decoded image dimensions to reduce decompression-bomb and memory risks.
 * These bounds are checked from image headers before pixel allocation.
 */
internal object VisionImageDimensionsPolicy {
    const val MAX_DIMENSION = 8_192
    const val MAX_PIXEL_COUNT = 40_000_000L

    fun validate(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) {
            return "The selected file is not a decodable image."
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            return "Image dimensions must not exceed 8192 pixels on either side."
        }
        if (width.toLong() * height.toLong() > MAX_PIXEL_COUNT) {
            return "The decoded image may not exceed 40 megapixels."
        }
        return null
    }
}

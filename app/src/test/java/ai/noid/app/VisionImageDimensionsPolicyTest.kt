package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisionImageDimensionsPolicyTest {
    @Test
    fun acceptsNormalImageDimensions() {
        assertNull(VisionImageDimensionsPolicy.validate(1920, 1080))
    }

    @Test
    fun rejectsUnknownOrInvalidDimensions() {
        assertEquals(
            "The selected file is not a decodable image.",
            VisionImageDimensionsPolicy.validate(0, 1080)
        )
        assertEquals(
            "The selected file is not a decodable image.",
            VisionImageDimensionsPolicy.validate(1920, -1)
        )
    }

    @Test
    fun rejectsExcessiveSideLength() {
        assertEquals(
            "Image dimensions must not exceed 8192 pixels on either side.",
            VisionImageDimensionsPolicy.validate(8193, 100)
        )
    }

    @Test
    fun rejectsExcessiveDecodedPixelCountWithoutIntegerOverflow() {
        assertEquals(
            "The decoded image may not exceed 40 megapixels.",
            VisionImageDimensionsPolicy.validate(8000, 6000)
        )
        assertNull(VisionImageDimensionsPolicy.validate(8000, 5000))
    }
}

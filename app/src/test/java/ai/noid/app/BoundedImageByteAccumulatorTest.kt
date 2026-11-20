package ai.noid.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedImageByteAccumulatorTest {
    @Test
    fun appendsAcrossGrowthBoundariesAndReturnsIndependentCopy() {
        val accumulator = BoundedImageByteAccumulator(maxBytes = 16, initialCapacity = 2)
        accumulator.append(byteArrayOf(1, 2, 3), 0, 3)
        accumulator.append(byteArrayOf(4, 5, 6, 7), 1, 3)

        val result = accumulator.toByteArray()
        assertArrayEquals(byteArrayOf(1, 2, 3, 5, 6, 7), result)
        result[0] = 99
        assertArrayEquals(byteArrayOf(1, 2, 3, 5, 6, 7), accumulator.toByteArray())
        assertEquals(6, accumulator.size)
    }

    @Test
    fun rejectsWritesBeyondMaximumWithoutChangingStoredBytes() {
        val accumulator = BoundedImageByteAccumulator(maxBytes = 4, initialCapacity = 2)
        accumulator.append(byteArrayOf(1, 2, 3), 0, 3)

        assertThrows(IllegalArgumentException::class.java) {
            accumulator.append(byteArrayOf(4, 5), 0, 2)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), accumulator.toByteArray())
        assertEquals(3, accumulator.size)
    }

    @Test
    fun rejectsInvalidSourceRanges() {
        val accumulator = BoundedImageByteAccumulator(maxBytes = 8)
        assertThrows(IllegalArgumentException::class.java) {
            accumulator.append(byteArrayOf(1, 2), 1, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            accumulator.append(byteArrayOf(1), -1, 1)
        }
    }

    @Test
    fun clearResetsAccumulatorAndAllowsReuse() {
        val accumulator = BoundedImageByteAccumulator(maxBytes = 8, initialCapacity = 2)
        accumulator.append(byteArrayOf(1, 2, 3, 4), 0, 4)
        accumulator.clear()

        assertEquals(0, accumulator.size)
        assertArrayEquals(byteArrayOf(), accumulator.toByteArray())
        accumulator.append(byteArrayOf(8, 9), 0, 2)
        assertArrayEquals(byteArrayOf(8, 9), accumulator.toByteArray())
    }

    @Test
    fun rejectsInvalidCapacityConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedImageByteAccumulator(maxBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedImageByteAccumulator(maxBytes = 4, initialCapacity = 0)
        }
    }
}

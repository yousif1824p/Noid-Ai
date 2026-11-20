package ai.noid.app

/**
 * Bounded growable byte storage that wipes superseded and released buffers.
 * Returned arrays are independent copies owned by the caller.
 */
internal class BoundedImageByteAccumulator(
    private val maxBytes: Int,
    initialCapacity: Int = 8 * 1024
) {
    private var buffer: ByteArray
    var size: Int = 0
        private set

    init {
        require(maxBytes > 0)
        require(initialCapacity > 0)
        buffer = ByteArray(minOf(maxBytes, initialCapacity))
    }

    fun append(source: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= source.size - length) {
            "Invalid source range."
        }
        require(length <= maxBytes - size) {
            "The image exceeds the 12 MiB limit."
        }
        val required = size + length
        ensureCapacity(required)
        source.copyInto(buffer, destinationOffset = size, startIndex = offset, endIndex = offset + length)
        size = required
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    /** Zeroes the backing storage and resets the accumulator for disposal. */
    fun clear() {
        buffer.fill(0)
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var nextCapacity = buffer.size
        while (nextCapacity < required) {
            nextCapacity = minOf(maxBytes, maxOf(required, nextCapacity * 2))
        }
        val replacement = buffer.copyOf(nextCapacity)
        buffer.fill(0)
        buffer = replacement
    }
}

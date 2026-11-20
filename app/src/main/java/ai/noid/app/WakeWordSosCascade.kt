package ai.noid.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stateful second-order-section cascade using the DSP v1 row layout:
 * [b0, b1, b2, a1, a2, zi0, zi1].
 *
 * The stored zi values are the steady-state initial conditions for the complete
 * SOS cascade. They are all scaled by the first input sample of this cascade,
 * matching scipy.signal.sosfilt_zi(sos) * x[0]. State survives process() calls
 * and is discarded only by reset().
 */
internal class WakeWordSosCascade private constructor(
    private val rows: List<FloatArray>
) {
    private var state: Array<FloatArray>? = null

    init {
        require(rows.isNotEmpty())
        require(rows.all { row -> row.size == 7 && row.all { it.isFinite() } })
    }

    @Synchronized
    fun process(input: FloatArray): FloatArray {
        require(input.all { it.isFinite() }) { "Input contains non-finite samples" }
        if (input.isEmpty()) return FloatArray(0)

        val samples = input.copyOf()
        // Keep the update transactional: a numeric overflow in any section
        // cannot leave the retained streaming state partially modified.
        val nextState = state?.map { it.copyOf() }?.toTypedArray()
            ?: Array(rows.size) { index ->
                val firstSample = samples[0]
                floatArrayOf(rows[index][5] * firstSample, rows[index][6] * firstSample)
            }

        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            val sectionState = nextState[rowIndex]
            val b0 = row[0]
            val b1 = row[1]
            val b2 = row[2]
            val a1 = row[3]
            val a2 = row[4]
            var z1 = sectionState[0]
            var z2 = sectionState[1]
            for (sampleIndex in samples.indices) {
                val x = samples[sampleIndex]
                val y = b0 * x + z1
                val nextZ1 = b1 * x - a1 * y + z2
                val nextZ2 = b2 * x - a2 * y
                require(y.isFinite() && nextZ1.isFinite() && nextZ2.isFinite()) {
                    "SOS filter produced non-finite output"
                }
                z1 = nextZ1
                z2 = nextZ2
                samples[sampleIndex] = y
            }
            sectionState[0] = z1
            sectionState[1] = z2
        }
        state = nextState
        return samples
    }

    @Synchronized
    fun reset() {
        state = null
    }

    companion object {
        private val expectedShapes = mapOf(
            "highpass_sos" to listOf(4, 7),
            "notch_50hz_sos" to listOf(1, 7),
            "notch_60hz_sos" to listOf(1, 7)
        )

        fun fromPayload(
            payload: ByteArray,
            section: WakeWordDspArtifactParser.Section
        ): WakeWordSosCascade? {
            val expectedShape = expectedShapes[section.name] ?: return null
            if (section.shape != expectedShape ||
                section.elementCount != expectedShape[0] * expectedShape[1] ||
                section.offsetBytes < 0 || section.offsetBytes % 4 != 0
            ) return null
            val byteLength = section.elementCount.toLong() * 4L
            val end = section.offsetBytes.toLong() + byteLength
            if (end > payload.size.toLong()) return null

            return try {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                buffer.position(section.offsetBytes)
                val rows = List(expectedShape[0]) {
                    FloatArray(7) { buffer.float }
                }
                if (rows.any { row -> row.any { !it.isFinite() } }) return null
                WakeWordSosCascade(rows)
            } catch (_: Exception) {
                null
            }
        }

        /** Test and internal construction path for already validated DSP v1 rows. */
        fun fromRows(rows: List<FloatArray>): WakeWordSosCascade =
            WakeWordSosCascade(rows.map { it.copyOf() })
    }
}

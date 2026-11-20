package ai.noid.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeWordSosParityVectorTest {
    @Test
    fun matchesSharedScipyGoldenVectorAcrossAllHeedStages() {
        val lines = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("wakeword-sos-parity-v1.csv")
        ).bufferedReader().use { it.readLines() }
        val stages = linkedMapOf(
            "highpass" to mutableListOf<FloatArray>(),
            "notch50" to mutableListOf<FloatArray>(),
            "notch60" to mutableListOf<FloatArray>()
        )
        val vectors = mutableMapOf<String, FloatArray>()

        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val fields = line.split(",")
            val values = fields.drop(1).map(String::toFloat).toFloatArray()
            when (fields.first()) {
                "sos_highpass" -> stages.getValue("highpass") += values
                "sos_notch50" -> stages.getValue("notch50") += values
                "sos_notch60" -> stages.getValue("notch60") += values
                "input", "expected_highpass", "expected_notch50", "expected_notch60" ->
                    vectors[fields.first()] = values
            }
        }

        assertEquals(4, stages.getValue("highpass").size)
        assertEquals(1, stages.getValue("notch50").size)
        assertEquals(1, stages.getValue("notch60").size)

        val input = checkNotNull(vectors["input"])
        val highpass = WakeWordSosCascade.fromRows(stages.getValue("highpass"))
        val notch50 = WakeWordSosCascade.fromRows(stages.getValue("notch50"))
        val notch60 = WakeWordSosCascade.fromRows(stages.getValue("notch60"))

        val highpassed = highpass.process(input)
        assertArrayEquals(checkNotNull(vectors["expected_highpass"]), highpassed, 2e-5f)
        val without50 = notch50.process(highpassed)
        assertArrayEquals(checkNotNull(vectors["expected_notch50"]), without50, 2e-5f)
        val filtered = notch60.process(without50)
        assertArrayEquals(checkNotNull(vectors["expected_notch60"]), filtered, 2e-5f)

        val chunked = WakeWordSosCascade.fromRows(stages.getValue("highpass"))
        val split = chunked.process(input.copyOfRange(0, 5)) +
            chunked.process(input.copyOfRange(5, input.size))
        assertArrayEquals(highpassed, split, 2e-5f)
    }
}

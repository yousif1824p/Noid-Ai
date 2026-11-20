package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordSpottingAudioSessionTest {
    private val descriptor = KeywordModelDescriptor(
        modelId = "test-local", displayName = "Test", languageTag = "ar-IQ",
        wakePhrase = "يا NOID", sampleRateHz = 16_000, frameLengthSamples = 4,
        localOnly = true, modelVersion = "1"
    )

    private class FakeSource(private val chunks: List<ShortArray>) : Pcm16AudioSource {
        var started = false
        var stopped = false
        var closed = false
        private var chunkIndex = 0
        private var chunkOffset = 0

        override fun start(): Boolean { started = true; return true }

        override fun read(target: ShortArray, offset: Int, length: Int): Int {
            val chunk = chunks.getOrNull(chunkIndex) ?: return -1
            val count = minOf(length, chunk.size - chunkOffset)
            chunk.copyInto(target, offset, chunkOffset, chunkOffset + count)
            chunkOffset += count
            if (chunkOffset == chunk.size) {
                chunkIndex++
                chunkOffset = 0
            }
            return count
        }

        override fun stop() { stopped = true }
        override fun close() { closed = true }
    }

    private class FakeRuntime(
        override val descriptor: KeywordModelDescriptor,
        private val result: KeywordSpottingEvent? = null
    ) : KeywordSpottingRuntime {
        var frames = 0
        var closed = false
        override fun processAudioFrame(pcm16: ShortArray): KeywordSpottingEvent? {
            frames++
            assertEquals(descriptor.frameLengthSamples, pcm16.size)
            return result
        }
        override fun close() { closed = true }
    }

    @Test
    fun joinsPartialReadsIntoExactModelFrames() {
        val source = FakeSource(listOf(
            shortArrayOf(1, 2), shortArrayOf(3), shortArrayOf(4, 5, 6, 7, 8)
        ))
        val runtime = FakeRuntime(descriptor)
        val session = KeywordSpottingAudioSession(source, runtime, { runtime.frames >= 2 }, {})
        assertNull(session.run())
        assertEquals(2, runtime.frames)
        assertTrue(source.started)
        assertTrue(source.stopped)
        assertTrue(source.closed)
        assertTrue(runtime.closed)
    }

    @Test
    fun emitsOnlyBoundaryValidatedEvents() {
        val source = FakeSource(listOf(shortArrayOf(1, 2, 3, 4)))
        val event = KeywordSpottingEvent.Detected("يا NOID", 0.92f)
        val runtime = FakeRuntime(descriptor, event)
        val received = mutableListOf<KeywordSpottingEvent>()
        val session = KeywordSpottingAudioSession(
            source, runtime, { received.isNotEmpty() }, received::add
        )
        assertNull(session.run())
        assertEquals(listOf(event), received)
    }

    @Test
    fun rejectsNonLocalModelBeforeStartingMicrophoneAndCleansUp() {
        val source = FakeSource(emptyList())
        val runtime = FakeRuntime(descriptor.copy(localOnly = false))
        val session = KeywordSpottingAudioSession(source, runtime, { false }, {})
        assertEquals("يُسمح بنموذج محلي فقط.", session.run())
        assertFalse(source.started)
        assertTrue(source.closed)
        assertTrue(runtime.closed)
    }

    @Test
    fun rejectsInvalidDetectionAndAlwaysCleansUp() {
        val source = FakeSource(listOf(shortArrayOf(1, 2, 3, 4)))
        val runtime = FakeRuntime(descriptor, KeywordSpottingEvent.Detected("wrong", 0.5f))
        val session = KeywordSpottingAudioSession(source, runtime, { false }, {})
        assertEquals("عبارة التنبيه المكتشفة لا تطابق العبارة المضبوطة.", session.run())
        assertTrue(source.closed)
        assertTrue(runtime.closed)
    }

    @Test
    fun stopRequestEndsAfterCurrentFrameAndClosesResources() {
        val source = FakeSource(listOf(
            shortArrayOf(1, 2, 3, 4), shortArrayOf(5, 6, 7, 8)
        ))
        val runtime = FakeRuntime(descriptor)
        val session = KeywordSpottingAudioSession(source, runtime, { runtime.frames >= 1 }, {})
        assertNull(session.run())
        assertEquals(1, runtime.frames)
        assertTrue(source.closed)
        assertTrue(runtime.closed)
    }
}

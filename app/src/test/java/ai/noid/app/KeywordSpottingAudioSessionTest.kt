package ai.noid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordSpottingAudioSessionTest {
    private val descriptor = KeywordModelDescriptor(
        modelId = "test-local", displayName = "Test", languageTag = "ar-IQ",
        wakePhrase = "يا NOID", sampleRateHz = 16_000, frameLengthSamples = 80,
        localOnly = true, modelVersion = "1"
    )

    private class FakeSource(
        private val chunks: List<ShortArray>,
        private val zeroReadsBeforeData: Int = 0
    ) : Pcm16AudioSource {
        var started = false
        var stopped = false
        var closed = false
        var startCalls = 0
        var stopCalls = 0
        var closeCalls = 0
        private var chunkIndex = 0
        private var chunkOffset = 0
        private var zeroReads = 0

        override fun start(): Boolean {
            startCalls++
            started = true
            return true
        }

        override fun read(target: ShortArray, offset: Int, length: Int): Int {
            if (zeroReads < zeroReadsBeforeData) {
                zeroReads++
                return 0
            }
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

        override fun stop() { stopCalls++; stopped = true }
        override fun close() { closeCalls++; closed = true }
    }

    private class FakeRuntime(
        override val descriptor: KeywordModelDescriptor,
        private val result: KeywordSpottingEvent? = null
    ) : KeywordSpottingRuntime {
        var frames = 0
        var closed = false
        var closeCalls = 0
        override fun processAudioFrame(pcm16: ShortArray): KeywordSpottingEvent? {
            frames++
            assertEquals(descriptor.frameLengthSamples, pcm16.size)
            return result
        }
        override fun close() { closeCalls++; closed = true }
    }

    private fun frame(start: Int = 1) = ShortArray(80) { (it + start).toShort() }

    @Test
    fun joinsPartialReadsIntoExactModelFrames() {
        val first = frame()
        val second = frame(100)
        val source = FakeSource(listOf(first.copyOfRange(0, 20), first.copyOfRange(20, 80), second))
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
        val source = FakeSource(listOf(frame()))
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
    fun rejectsInvalidDescriptorBeforeStartingMicrophoneAndCleansUp() {
        val source = FakeSource(emptyList())
        val runtime = FakeRuntime(descriptor.copy(localOnly = false))
        val session = KeywordSpottingAudioSession(source, runtime, { false }, {})
        assertEquals("بيانات نموذج اكتشاف الكلمة غير صالحة.", session.run())
        assertFalse(source.started)
        assertTrue(source.closed)
        assertTrue(runtime.closed)
    }

    @Test
    fun rejectsInvalidDetectionAndAlwaysCleansUp() {
        val source = FakeSource(listOf(frame()))
        val runtime = FakeRuntime(descriptor, KeywordSpottingEvent.Detected("wrong", 0.5f))
        val session = KeywordSpottingAudioSession(source, runtime, { false }, {})
        assertEquals("عبارة التنبيه المكتشفة لا تطابق العبارة المضبوطة.", session.run())
        assertTrue(source.closed)
        assertTrue(runtime.closed)
    }

    @Test
    fun stopsAfterRepeatedEmptyReadsInsteadOfSpinningForever() {
        val source = FakeSource(listOf(frame()), zeroReadsBeforeData = 8)
        val runtime = FakeRuntime(descriptor)
        val session = KeywordSpottingAudioSession(source, runtime, { false }, {})
        assertEquals(
            "مصدر الصوت لا يعيد بيانات؛ أُوقفت الجلسة لحماية المعالج.",
            session.run()
        )
        assertEquals(0, runtime.frames)
        assertTrue(source.closed)
        assertTrue(runtime.closed)
    }

    @Test
    fun stopRequestEndsAfterCurrentFrameAndClosesResources() {
        val source = FakeSource(listOf(frame(), frame(100)))
        val runtime = FakeRuntime(descriptor)
        val session = KeywordSpottingAudioSession(source, runtime, { runtime.frames >= 1 }, {})
        assertNull(session.run())
        assertEquals(1, runtime.frames)
        assertTrue(source.closed)
        assertTrue(runtime.closed)
    }

    @Test
    fun closeIsIdempotentAndPreventsLaterMicrophoneStart() {
        val source = FakeSource(listOf(frame()))
        val runtime = FakeRuntime(descriptor)
        val session = KeywordSpottingAudioSession(source, runtime, { false }, {})

        session.close()
        session.close()
        assertNull(session.run())

        assertEquals(0, source.startCalls)
        assertEquals(1, source.stopCalls)
        assertEquals(1, source.closeCalls)
        assertEquals(1, runtime.closeCalls)
        assertEquals(0, runtime.frames)
    }
}

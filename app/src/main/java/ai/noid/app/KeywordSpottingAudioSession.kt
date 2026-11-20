package ai.noid.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * PCM16 microphone source. The owner must obtain RECORD_AUDIO permission and start
 * this source only from a user-authorized foreground session.
 */
internal interface Pcm16AudioSource : AutoCloseable {
    fun start(): Boolean
    fun read(target: ShortArray, offset: Int, length: Int): Int
    fun stop()
    override fun close()
}

/** Android microphone adapter; it never starts itself from construction. */
internal class AndroidPcm16AudioSource(
    private val sampleRateHz: Int,
    private val frameLengthSamples: Int
) : Pcm16AudioSource {
    private var recorder: AudioRecord? = null

    override fun start(): Boolean {
        if (sampleRateHz !in 8_000..48_000 || frameLengthSamples !in 80..4_800) return false
        if (recorder != null) return false
        val minimumBytes = AudioRecord.getMinBufferSize(
            sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBytes <= 0) return false
        val bufferBytes = maxOf(minimumBytes, frameLengthSamples * Short.SIZE_BYTES * 2)
        val created = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRateHz, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes
            )
        } catch (_: SecurityException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return false
        }
        return try {
            created.startRecording()
            if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                created.release()
                false
            } else {
                recorder = created
                true
            }
        } catch (_: SecurityException) {
            created.release()
            false
        } catch (_: IllegalStateException) {
            created.release()
            false
        }
    }

    override fun read(target: ShortArray, offset: Int, length: Int): Int =
        recorder?.read(target, offset, length, AudioRecord.READ_BLOCKING)
            ?: AudioRecord.ERROR_INVALID_OPERATION

    override fun stop() {
        recorder?.let(::stopRecorder)
    }

    override fun close() {
        val active = recorder ?: return
        recorder = null
        stopRecorder(active)
        active.release()
    }

    private fun stopRecorder(active: AudioRecord) {
        if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                active.stop()
            } catch (_: IllegalStateException) {
                // Best-effort cleanup; release remains mandatory.
            }
        }
    }
}

private const val MAX_CONSECUTIVE_EMPTY_READS = 8

/**
 * Converts arbitrary AudioRecord read sizes into exact model frames and validates
 * every frame/event at the model boundary. Call from a worker thread.
 *
 * Start and close are serialized so a concurrent stop cannot close the source
 * immediately before run() starts it. close() is idempotent and may be called
 * from the UI thread to unblock a worker blocked in read().
 */
internal class KeywordSpottingAudioSession(
    private val source: Pcm16AudioSource,
    private val runtime: KeywordSpottingRuntime,
    private val shouldStop: () -> Boolean,
    private val onEvent: (KeywordSpottingEvent) -> Unit
) : AutoCloseable {
    private val resourceLock = Any()
    private val runtimeLock = Any()
    @Volatile private var closed = false

    fun run(): String? {
        try {
            val descriptor = runtime.descriptor
            if (!descriptor.isValid()) return "بيانات نموذج اكتشاف الكلمة غير صالحة."
            if (!descriptor.localOnly) return "يُسمح بنموذج محلي فقط."
            val started = synchronized(resourceLock) {
                !closed && source.start()
            }
            if (!started) {
                return if (closed) null else "تعذر بدء مصدر الصوت."
            }
            val frame = ShortArray(descriptor.frameLengthSamples)
            while (!closed && !shouldStop()) {
                var filled = 0
                var consecutiveEmptyReads = 0
                while (filled < frame.size && !closed && !shouldStop()) {
                    val count = source.read(frame, filled, frame.size - filled)
                    if (count < 0) return "تعذر قراءة إطار الصوت."
                    if (count == 0) {
                        consecutiveEmptyReads++
                        if (consecutiveEmptyReads >= MAX_CONSECUTIVE_EMPTY_READS) {
                            return "مصدر الصوت لا يعيد بيانات؛ أُوقفت الجلسة لحماية المعالج."
                        }
                        continue
                    }
                    consecutiveEmptyReads = 0
                    if (count > frame.size - filled) return "مصدر الصوت أعاد حجماً غير صالح."
                    filled += count
                }
                if (closed || shouldStop()) break
                val frameError = KeywordSpottingBoundaryPolicy.validateAudioFrame(frame, descriptor)
                if (frameError != null) return frameError
                val event = try {
                    synchronized(runtimeLock) {
                        if (closed) null else runtime.processAudioFrame(frame.copyOf())
                    }
                } catch (_: Exception) {
                    return "تعذر تشغيل نموذج اكتشاف الكلمة."
                }
                // A UI-thread close may have waited for inference to finish. Never
                // publish a result after that close request has been accepted.
                if (closed) break
                if (event != null) {
                    val eventError = KeywordSpottingBoundaryPolicy.validateEvent(event, descriptor)
                    if (eventError != null) return eventError
                    if (closed) break
                    onEvent(event)
                    if (event is KeywordSpottingEvent.Unavailable) return event.reason
                }
            }
            return null
        } finally {
            close()
        }
    }

    override fun close() {
        synchronized(resourceLock) {
            if (closed) return
            closed = true
            try {
                source.stop()
            } finally {
                try {
                    source.close()
                } finally {
                    // Do not dispose the inference engine while processAudioFrame()
                    // is using it. Marking closed first prevents any new inference.
                    synchronized(runtimeLock) {
                        runtime.close()
                    }
                }
            }
        }
    }
}

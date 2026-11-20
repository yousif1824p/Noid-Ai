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
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBytes <= 0) return false
        val frameBytes = frameLengthSamples * Short.SIZE_BYTES
        val bufferBytes = maxOf(minimumBytes, frameBytes * 2)
        val created = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
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

    override fun read(target: ShortArray, offset: Int, length: Int): Int {
        val active = recorder ?: return AudioRecord.ERROR_INVALID_OPERATION
        return active.read(target, offset, length, AudioRecord.READ_BLOCKING)
    }

    override fun stop() {
        val active = recorder ?: return
        if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                active.stop()
            } catch (_: IllegalStateException) {
                // A concurrent platform stop is already in progress.
            }
        }
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

/**
 * Converts arbitrary AudioRecord read sizes into exact model frames and validates
 * every frame/event at the model boundary. Call from a worker thread.
 */
internal class KeywordSpottingAudioSession(
    private val source: Pcm16AudioSource,
    private val runtime: KeywordSpottingRuntime,
    private val shouldStop: () -> Boolean,
    private val onEvent: (KeywordSpottingEvent) -> Unit
) : AutoCloseable {
    @Volatile private var closed = false

    fun run(): String? {
        val descriptor = runtime.descriptor
        if (!descriptor.isValid()) return "بيانات نموذج اكتشاف الكلمة غير صالحة."
        if (!descriptor.localOnly) return "يُسمح بنموذج محلي فقط."
        if (!source.start()) return "تعذر بدء مصدر الصوت."
        val frame = ShortArray(descriptor.frameLengthSamples)
        try {
            while (!closed && !shouldStop()) {
                var filled = 0
                while (filled < frame.size && !closed && !shouldStop()) {
                    val count = source.read(frame, filled, frame.size - filled)
                    if (count < 0) return "تعذر قراءة إطار الصوت."
                    if (count == 0) continue
                    if (count > frame.size - filled) return "مصدر الصوت أعاد حجماً غير صالح."
                    filled += count
                }
                if (closed || shouldStop()) break
                val frameError = KeywordSpottingBoundaryPolicy.validateAudioFrame(frame, descriptor)
                if (frameError != null) return frameError
                val event = try {
                    runtime.processAudioFrame(frame.copyOf())
                } catch (_: Exception) {
                    return "تعذر تشغيل نموذج اكتشاف الكلمة."
                }
                if (event != null) {
                    val eventError = KeywordSpottingBoundaryPolicy.validateEvent(event, descriptor)
                    if (eventError != null) return eventError
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
        if (closed) return
        closed = true
        try {
            source.stop()
        } finally {
            try {
                source.close()
            } finally {
                runtime.close()
            }
        }
    }
}

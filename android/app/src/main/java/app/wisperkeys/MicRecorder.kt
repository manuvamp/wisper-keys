package app.wisperkeys

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.sqrt

/**
 * 16 kHz mono 16-bit PCM in 40 ms frames, straight from the mic. VOICE_RECOGNITION is the source tuned for speech
 * recognition (no aggressive noise gating or volume pumping). Frames go out as they arrive; [stop] waits for the
 * last one so nothing at the end of what you said is cut.
 */
class MicRecorder(private val onFrame: (ByteArray, Int) -> Unit, private val onLevel: (Float) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Speech-tuned source first; some devices only give it up after a moment, so fall back to the plain mic. */
    fun start(): Boolean {
        if (running) return true
        val rec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION) ?: open(MediaRecorder.AudioSource.MIC) ?: return false
        running = true
        thread = Thread({ loop(rec) }, "wisper-mic").also { it.start() }
        return true
    }

    @SuppressLint("MissingPermission")   // checked by the caller
    private fun open(source: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        } catch (e: Exception) {
            return null
        }
        if (rec.state == AudioRecord.STATE_INITIALIZED) {
            try {
                rec.startRecording()
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) return rec
            } catch (_: Exception) {}
        }
        rec.release()
        return null
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val pcm = ShortArray(FRAME)
        val bytes = ByteArray(FRAME * 2)
        try {
            while (running) send(pcm, bytes, rec.read(pcm, 0, FRAME))
            while (true) {   // drain what's still buffered after you let go
                val n = rec.read(pcm, 0, FRAME, AudioRecord.READ_NON_BLOCKING)
                if (n <= 0) break
                send(pcm, bytes, n)
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun send(pcm: ShortArray, bytes: ByteArray, n: Int) {
        if (n <= 0) return
        var sum = 0.0
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = (v shr 8 and 0xFF).toByte()
            sum += v.toDouble() * v
        }
        onFrame(bytes, n * 2)
        onLevel((sqrt(sum / n) / 32768.0).toFloat())
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    companion object {
        const val RATE = 16000
        const val FRAME = 640   // 40 ms
    }
}

package io.github.ccch1mneyyy.dsh_app.nativemedia

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.SystemClock

/**
 * Listens for the person speaking while a plugin reads: 16 kHz mono from the voice-communication
 * source (the one Android applies echo cancellation to), with the platform's
 * [AcousticEchoCanceler] on when the device has one, through a [SpeechGate]. Frames are measured
 * and dropped on the capture thread; only the gate's edges reach the main thread.
 *
 * v1.4 `mic`: with [onFrame] set, the same recorder hands every 20 ms frame to that callback and
 * no gate runs.
 *
 * Lifecycle: the capture thread owns closing. [stop] only signals and waits; it never releases an
 * [AudioRecord] the thread may still be reading. The thread's `finally` stops and releases the
 * recorder and the echo canceller, and clears `running`, so "join timed out" can never mean
 * "released under a running thread". [start] refuses while the previous thread is alive.
 */
class VoiceCapture(
    private val main: Handler,
    private val onEdge: (SpeechGate.Edge) -> Unit,
    private val onError: (String) -> Unit,
    private val onFrame: ((ShortArray, Int) -> Unit)? = null,
) {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var thread: Thread? = null
    private var gate: SpeechGate? = null

    @Volatile private var running = false
    @Volatile private var playing = false
    private var session = 0

    /** The platform echo canceller is on for this capture. */
    var echoCancelled = false
        private set

    /** A recorder is open and its thread is running. */
    val active get() = running && record != null

    /** A stopped capture whose thread has not exited yet; starting again would be a second read. */
    val closing get() = thread?.isAlive == true

    @SuppressLint("MissingPermission") // NativeMediaPlugin checks RECORD_AUDIO first.
    fun start(): Boolean {
        // One recorder, one thread. Never open a second one over a thread that is still exiting.
        if (thread?.isAlive == true) return false
        // A previous capture that ended on its own left its recorder behind: close it here.
        record?.let { runCatching { it.stop() }; runCatching { it.release() } }
        record = null
        runCatching { aec?.release() }
        aec = null
        gate = null

        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, RATE / 5 * 2), // 200 ms
            )
        }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        echoCancelled = false
        val effect = if (runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)) {
            runCatching { AcousticEchoCanceler.create(r.audioSessionId) }.getOrNull()?.also { e ->
                echoCancelled = runCatching { e.setEnabled(true) == AudioEffect.SUCCESS && e.enabled }.getOrDefault(false)
            }
        } else null
        val started = runCatching { r.startRecording() }.isSuccess && r.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) {
            runCatching { effect?.release() }
            r.release()
            return false
        }
        record = r
        aec = effect
        // 快's 700 ms: what voice input always had (the call's pause is its own setting).
        val tap = onFrame
        val g = if (tap == null) SpeechGate(echoCancelled, TurnPause.QUICK).also { gate = it } else null
        if (playing) g?.setPlaying(true, SystemClock.elapsedRealtime())
        running = true
        val mine = ++session
        val t = Thread({ captureLoop(r, effect, g, mine) }, "dsh-plugin-voice")
        thread = t
        t.start()
        return true
    }

    /** The owner's clip started or stopped playing: the gate is stricter while it plays. */
    fun setPlaying(on: Boolean) {
        playing = on
        val g = gate ?: return
        synchronized(g) { g.setPlaying(on, SystemClock.elapsedRealtime()) }
    }

    private fun captureLoop(r: AudioRecord, effect: AcousticEchoCanceler?, g: SpeechGate?, mine: Int) {
        val frame = ShortArray((RATE * SpeechGate.FRAME_MS / 1000).toInt())
        var filled = 0
        try {
            while (running) {
                val n = r.read(frame, filled, frame.size - filled)
                if (!running) break
                if (n < 0) {
                    post(mine) { onError("capture") }
                    break
                }
                filled += n
                if (filled < frame.size) continue
                filled = 0
                val tap = onFrame
                if (tap != null) {
                    tap(frame, frame.size)
                    continue
                }
                val level = SpeechGate.levelOf(frame, frame.size)
                val edge = synchronized(g!!) { g.consume(level, SystemClock.elapsedRealtime()) }
                if (edge != SpeechGate.Edge.NONE) post(mine) { onEdge(edge) }
            }
        } catch (_: RuntimeException) {
            post(mine) { onError("capture") }
        } finally {
            // The thread closes: stop() may have timed out, and releasing here is the only point
            // where no read can be in flight on this recorder.
            running = false
            runCatching { r.stop() }
            runCatching { effect?.release() }
            runCatching { r.release() }
            frame.fill(0)
            // A newer capture (different session) owns the fields now; this one must not clear them.
            main.post { if (mine == session) { record = null; aec = null; gate = null } }
        }
    }

    private fun post(mine: Int, block: () -> Unit) {
        main.post { if (mine == session && running) block() }
    }

    /**
     * Ends the capture and reports whether the thread really exited. False means "still closing":
     * the recorder and the echo canceller stay owned by the thread until it leaves, and [start]
     * refuses until then.
     */
    fun stop(): Boolean {
        running = false
        val r = record
        runCatching { r?.stop() }
        val t = thread
        if (t != null) runCatching { t.join(JOIN_MS) }
        val exited = t?.isAlive != true
        if (exited) {
            record = null
            aec = null
            gate = null
            thread = null
        }
        return exited
    }

    private companion object {
        const val RATE = 16000
        const val JOIN_MS = 300L
    }
}

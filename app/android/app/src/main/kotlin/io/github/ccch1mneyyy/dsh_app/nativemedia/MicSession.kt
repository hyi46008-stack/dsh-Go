package io.github.ccch1mneyyy.dsh_app.nativemedia

/**
 * v1.4 `mic`: one capture session's state — its generation, whether frames may flow, and whether
 * the recorder is still closing.
 *
 * Frames are stamped with the generation they were captured in; when a session ends (stop, revoke,
 * restart, error) the generation changes at once, so frames still in the queue can never be sent
 * under the next lease. Closing is a separate phase: frames stop immediately, but the microphone
 * arbiter and the indicator stay until the capture thread has really exited.
 *
 * Main thread writes; the capture thread reads [active]/[generation]; every field is volatile, so
 * no lock is needed on the hot path. The transitions are synchronized only to keep begin/stop
 * atomic against each other.
 */
class MicSession {
    enum class Phase { IDLE, ACTIVE, CLOSING }

    @Volatile
    var phase = Phase.IDLE
        private set

    /** Changes on every begin and on every stop: a frame is only valid in its own generation. */
    @Volatile
    var generation = 0
        private set

    val active get() = phase == Phase.ACTIVE
    val closing get() = phase == Phase.CLOSING

    /** Starts a session and returns its generation; any older frame becomes invalid. */
    @Synchronized
    fun begin(): Int {
        generation += 1
        phase = Phase.ACTIVE
        return generation
    }

    /**
     * Ends the current session. Returns true when the recorder is already closed (nothing more to
     * release); false means CLOSING — keep the arbiter and the indicator until [threadExited].
     */
    @Synchronized
    fun stop(threadExited: Boolean): Boolean {
        generation += 1
        phase = if (threadExited) Phase.IDLE else Phase.CLOSING
        return threadExited
    }

    /** The capture thread confirmed its exit: CLOSING → IDLE, once. */
    @Synchronized
    fun threadExited(): Boolean {
        if (phase != Phase.CLOSING) return false
        phase = Phase.IDLE
        return true
    }

    /** True only for a frame captured in the current, running generation. */
    fun owns(frameGeneration: Int) = phase == Phase.ACTIVE && frameGeneration == generation
}

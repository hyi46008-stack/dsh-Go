package io.github.ccch1mneyyy.dsh_app.nativemedia

/**
 * v1.4 `mic`: the bounded hand-off between the capture thread and the main-thread sender.
 *
 * The microphone must never block and must never grow memory: a full queue drops its **oldest**
 * frame (the newest audio is the useful one), counts the drop, and returns at once. Sequence
 * numbers are assigned at capture time and travel with the frame, so a plugin sees the gap.
 * Main and capture threads share it; every method is synchronized on the queue.
 */
class MicQueue(private val capacity: Int = 8) {
    class Frame(val generation: Int, val seq: Int, val bytes: ByteArray)

    private val items = ArrayDeque<Frame>(capacity)

    /** Frames dropped because the queue was full. */
    var dropped = 0L
        private set

    /** The most frames this queue can hold. */
    val max = capacity

    /** Always true: a full queue makes room by dropping the oldest frame. */
    @Synchronized
    fun offer(frame: Frame): Boolean {
        if (items.size >= capacity) {
            items.removeFirst()
            dropped++
        }
        items.addLast(frame)
        return true
    }

    @Synchronized
    fun take(): Frame? = if (items.isEmpty()) null else items.removeFirst()

    @Synchronized
    fun clear() = items.clear()

    @Synchronized
    fun size(): Int = items.size
}

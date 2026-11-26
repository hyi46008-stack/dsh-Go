package io.github.ccch1mneyyy.dsh_app.nativemedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** v1.4 `mic`: the bounded hand-off must drop the oldest frame, never grow, never block. */
class MicQueueTest {
    private fun frame(seq: Int, generation: Int = 1) = MicQueue.Frame(generation, seq, ByteArray(640))

    @Test
    fun aFullQueueDropsTheOldestFrame() {
        val q = MicQueue(3)
        for (seq in 1..5) q.offer(frame(seq))
        assertEquals(3, q.size())
        assertEquals(2L, q.dropped)
        assertEquals(3, q.take()?.seq)
        assertEquals(4, q.take()?.seq)
        assertEquals(5, q.take()?.seq)
    }

    @Test
    fun takingFromAnEmptyQueueIsNull() {
        assertNull(MicQueue(2).take())
    }

    @Test
    fun clearDropsEverythingAndKeepsTheCount() {
        val q = MicQueue(4)
        repeat(6) { q.offer(frame(it)) }
        q.clear()
        assertEquals(0, q.size())
        assertEquals(2L, q.dropped)
        assertNull(q.take())
    }

    @Test
    fun framesKeepTheirGenerationSeqAndBytes() {
        val q = MicQueue(2)
        val bytes = ByteArray(640) { 7 }
        q.offer(MicQueue.Frame(3, 42, bytes))
        val got = q.take()
        assertEquals(3, got?.generation)
        assertEquals(42, got?.seq)
        assertSame(bytes, got?.bytes)
    }
}

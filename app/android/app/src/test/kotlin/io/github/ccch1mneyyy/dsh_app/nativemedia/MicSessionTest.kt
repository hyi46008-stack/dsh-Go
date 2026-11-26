package io.github.ccch1mneyyy.dsh_app.nativemedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4 `mic`: session generations and the closing phase. These are the two rules the microphone
 * relies on — old frames can never be sent under a new lease, and a recorder that has not exited
 * keeps the arbiter and the indicator.
 */
class MicSessionTest {
    @Test
    fun eachBeginHasANewGeneration() {
        val session = MicSession()
        val first = session.begin()
        assertTrue(session.active)
        val second = session.begin()
        assertTrue(second > first)
        assertEquals(second, session.generation)
    }

    @Test
    fun onlyTheCurrentGenerationIsOwned() {
        val session = MicSession()
        val first = session.begin()
        assertTrue(session.owns(first))
        val second = session.begin()
        assertFalse("a frame of the previous session is not owned", session.owns(first))
        assertTrue(session.owns(second))
    }

    @Test
    fun aStopThatCouldNotCloseBecomesClosing() {
        val session = MicSession()
        session.begin()
        assertFalse(session.stop(threadExited = false))
        assertTrue(session.closing)
        assertFalse(session.active)
        assertFalse("no frame is valid while closing", session.owns(session.generation))
    }

    @Test
    fun closingEndsOnlyWhenTheThreadReallyExited() {
        val session = MicSession()
        session.begin()
        session.stop(threadExited = false)
        assertTrue(session.threadExited())
        assertEquals(MicSession.Phase.IDLE, session.phase)
        assertFalse("a second exit is not a transition", session.threadExited())
    }

    @Test
    fun aStopThatClosedGoesStraightToIdle() {
        val session = MicSession()
        session.begin()
        assertTrue(session.stop(threadExited = true))
        assertEquals(MicSession.Phase.IDLE, session.phase)
        assertFalse(session.closing)
    }

    @Test
    fun restartAfterClosingInvalidatesTheOldFrames() {
        val session = MicSession()
        val old = session.begin()
        session.stop(threadExited = false)
        val fresh = session.begin()
        assertEquals(MicSession.Phase.ACTIVE, session.phase)
        assertTrue(fresh > old)
        assertFalse(session.owns(old))
        assertTrue(session.owns(fresh))
    }

    @Test
    fun repeatedStopsAreIdempotent() {
        val session = MicSession()
        session.begin()
        assertFalse(session.stop(threadExited = false))
        assertFalse(session.stop(threadExited = false))
        assertTrue(session.closing)
        assertTrue(session.threadExited())
        assertTrue(session.stop(threadExited = true))
        assertFalse(session.threadExited())
    }

    @Test
    fun aFrameOnlyTravelsWithItsOwnGeneration() {
        val session = MicSession()
        val first = session.begin()
        val queue = MicQueue(4)
        queue.offer(MicQueue.Frame(first, 1, ByteArray(640)))
        // A restart invalidates the queued frame before the pump could take it.
        val second = session.begin()
        assertFalse(session.owns(queue.take()!!.generation))
        queue.offer(MicQueue.Frame(second, 2, ByteArray(640)))
        assertTrue(session.owns(queue.take()!!.generation))
    }
}

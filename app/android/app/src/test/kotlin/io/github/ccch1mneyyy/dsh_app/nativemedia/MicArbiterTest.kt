package io.github.ccch1mneyyy.dsh_app.nativemedia

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MicArbiterTest {
    @After fun tearDown() = MicArbiter.reset()

    /** A plugin holder whose recorder closes on demand. */
    private class FakeHolder(private val stopNow: () -> Boolean) : MicArbiter.Holder {
        var yields = 0
            private set

        override fun yieldMic(): Boolean {
            yields++
            return stopNow()
        }
    }

    @Test fun theFirstDictationGetsAToken() {
        val token = MicArbiter.beginDictation()
        assertNotNull(token)
        assertTrue(MicArbiter.dictating)
    }

    @Test fun aSecondRequestWhileDictatingIsRefused() {
        val first = MicArbiter.beginDictation()
        assertNotNull(first)
        assertNull("one dictation session at a time", MicArbiter.beginDictation())
        assertNull(MicArbiter.beginDictation())
        assertTrue(MicArbiter.dictating)
    }

    @Test fun twoIndependentModulesGetAtMostOneToken() {
        val pet = MicArbiter.beginDictation()
        val voice = MicArbiter.beginDictation()
        assertNotNull(pet)
        assertNull("the second module must be refused", voice)
        MicArbiter.endDictation(pet!!)
        assertNotNull("the next module may start once the first ended", MicArbiter.beginDictation())
    }

    @Test fun endingTheSessionLetsTheNextRequestIn() {
        val first = MicArbiter.beginDictation()!!
        MicArbiter.endDictation(first)
        assertFalse(MicArbiter.dictating)
        val second = MicArbiter.beginDictation()
        assertNotNull(second)
        assertTrue(second != first)
    }

    @Test fun anOldTokenCannotEndTheNewSession() {
        val first = MicArbiter.beginDictation()!!
        MicArbiter.endDictation(first)
        val second = MicArbiter.beginDictation()!!
        MicArbiter.endDictation(first) // late release of the previous recorder
        assertTrue("the new session still owns the microphone", MicArbiter.dictating)
        MicArbiter.endDictation(second)
        assertFalse(MicArbiter.dictating)
    }

    @Test fun anUnknownTokenChangesNothing() {
        val token = MicArbiter.beginDictation()!!
        MicArbiter.endDictation(token + 1)
        assertTrue(MicArbiter.dictating)
        assertNull("still one session", MicArbiter.beginDictation())
        MicArbiter.endDictation(token)
        assertFalse(MicArbiter.dictating)
    }

    @Test fun aPluginThatStoppedHandsTheMicrophoneOver() {
        val plugin = FakeHolder { true }
        assertTrue(MicArbiter.acquire(plugin))
        val token = MicArbiter.beginDictation()
        assertNotNull(token)
        assertFalse("no plugin while dictating", MicArbiter.acquire(plugin))
        MicArbiter.endDictation(token!!)
        assertTrue("the arbiter no longer holds the closed plugin", MicArbiter.acquire(plugin))
    }

    @Test fun aClosingPluginRefusesDictationWithoutLockingItOut() {
        val closing = FakeHolder { false }
        assertTrue(MicArbiter.acquire(closing))
        assertNull("no valid token while closing", MicArbiter.beginDictation())
        assertFalse("a refused request does not start a session", MicArbiter.dictating)
        assertFalse("the closing plugin keeps the microphone", MicArbiter.acquire { true })
        assertNull("and a retry still refuses while it has not exited", MicArbiter.beginDictation())
        assertEquals(2, closing.yields)
    }

    @Test fun dictationSucceedsOnceTheOldRecorderHasExited() {
        var stopped = false
        val closing = FakeHolder { stopped }
        MicArbiter.acquire(closing)
        assertNull(MicArbiter.beginDictation())
        stopped = true // the capture thread exited between the two requests
        val token = MicArbiter.beginDictation()
        assertNotNull(token)
        MicArbiter.endDictation(token!!)
        assertTrue(MicArbiter.acquire { true })
    }

    @Test fun releasingAClosingHolderFreesTheClaim() {
        val closing = FakeHolder { false }
        MicArbiter.acquire(closing)
        assertNull(MicArbiter.beginDictation())
        MicArbiter.release(closing) // the close poller saw its thread exit
        val token = MicArbiter.beginDictation()
        assertNotNull(token)
        MicArbiter.endDictation(token!!)
        assertTrue(MicArbiter.acquire { true })
    }

    @Test fun anActiveDictationBlocksAPluginFromAcquiring() {
        val token = MicArbiter.beginDictation()!!
        assertFalse(MicArbiter.acquire(FakeHolder { true }))
        MicArbiter.endDictation(token)
        assertTrue(MicArbiter.acquire(FakeHolder { true }))
    }

    @Test fun concurrentRequestsProduceAtMostOneToken() {
        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val granted = AtomicInteger()
        val tokens = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                ready.countDown()
                go.await()
                MicArbiter.beginDictation()?.let { tokens.add(it); granted.incrementAndGet() }
            }
        }
        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("exactly one session may start", 1, granted.get())
        assertEquals(1, tokens.size)
        assertTrue(MicArbiter.dictating)
    }
}

package io.github.ccch1mneyyy.dsh_app.nativemedia

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.4 `mic`: the PCM16 conversion the capture thread runs for every delivered frame. */
class MicPcmTest {
    @Test
    fun samplesAreLittleEndianPcm16() {
        val frame = shortArrayOf(0, 1, -1, 0x1234, Short.MIN_VALUE, Short.MAX_VALUE)
        val bytes = MicPcm.bytes(frame, frame.size)
        assertEquals(12, bytes.size)
        assertArrayEquals(
            byteArrayOf(
                0, 0, // 0
                1, 0, // 1
                -1, -1, // -1
                0x34, 0x12, // 0x1234
                0, -128, // Short.MIN_VALUE
                -1, 127, // Short.MAX_VALUE
            ),
            bytes,
        )
    }

    @Test
    fun onlyTheReadSamplesAreConverted() {
        val frame = shortArrayOf(7, 7, 7, 7)
        assertArrayEquals(byteArrayOf(7, 0, 7, 0), MicPcm.bytes(frame, 2))
    }

    @Test
    fun aTwentyMillisecondFrameIsSixHundredAndFortyBytes() {
        assertEquals(640, MicPcm.bytes(ShortArray(320), 320).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCountBeyondTheFrameIsRefused() {
        MicPcm.bytes(ShortArray(4), 5)
    }
}

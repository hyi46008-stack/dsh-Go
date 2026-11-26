package io.github.ccch1mneyyy.dsh_app.nativemedia

/**
 * v1.4 `mic`: PCM16 little-endian bytes for one captured frame. Pure and allocation-only, so the
 * conversion the capture thread runs between reads is testable off-device.
 */
object MicPcm {
    /** 16 kHz mono PCM16: two bytes per sample, little endian. */
    fun bytes(frame: ShortArray, count: Int): ByteArray {
        require(count in 0..frame.size) { "count must fit the frame" }
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val sample = frame[i].toInt()
            out[i * 2] = sample.toByte()
            out[i * 2 + 1] = (sample shr 8).toByte()
        }
        return out
    }
}

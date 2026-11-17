package app.antresol.ui.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The video buffer is bound by the heap on every device; a low-RAM device gets 64 MB at most. */
class LowRamBufferTest {
    private val mb = 1024 * 1024
    private val gb = 1024L * 1024 * 1024

    /** ExoPlayer's default target (null from the rule), MB. */
    private val default = 138

    private fun megabytes(heap: Int, ramGb: Int, lowRam: Boolean = false): Int {
        // A device sold with N GB reports a little less.
        val bytes = videoBufferBytes(lowRam, ramGb * gb - 100 * mb, heap) ?: return default
        assertEquals(0, bytes % mb)
        return bytes / mb
    }

    @Test
    fun theBufferIsAThirdOfTheHeapOnEveryDevice() {
        // heap MB → buffer MB with 1, 2, 3 and 8 GB of memory.
        val table = listOf(
            48 to listOf(16, 16, 16, 16),
            64 to listOf(21, 21, 21, 21),
            128 to listOf(42, 42, 42, 42),
            192 to listOf(64, 64, 64, 64),
            256 to listOf(64, 64, 85, 85),
            512 to listOf(64, 64, default, default),
        )
        for ((heap, expected) in table) {
            assertEquals("heap $heap", expected, listOf(1, 2, 3, 8).map { megabytes(heap, it) })
        }
    }

    @Test
    fun theBufferLeavesTheHeapRoomAndStillPlaysARemux() {
        for (heap in listOf(48, 64, 128, 192, 256, 512)) {
            for (ram in listOf(1, 2, 3, 8)) {
                val buffer = megabytes(heap, ram)
                // Never more than a third of the heap (the default is 27 % of 512), nor more than the default.
                assertTrue("heap $heap, $ram GB: $buffer MB", buffer * 3 <= heap && buffer <= default)
                // An 80 Mbit/s remux is 10 MB a second: at least 1.6 s of it, and 4 s from a 128 MB heap on.
                val seconds = buffer * 8 / 80.0
                assertTrue("heap $heap, $ram GB: $seconds s", seconds >= if (heap >= 128) 4.0 else 1.6)
            }
        }
    }

    @Test
    fun aLowRamDeviceIsCappedWhateverItsMemoryAndHeap() {
        assertEquals(64, megabytes(heap = 384, ramGb = 4, lowRam = true))
        assertEquals(64, megabytes(heap = 512, ramGb = 8, lowRam = true))
        assertEquals(32, megabytes(heap = 96, ramGb = 4, lowRam = true))
        // Exactly 2 GB is low, a byte more is not.
        assertEquals(64 * mb, videoBufferBytes(false, 2 * gb, 256))
        assertEquals(85 * mb, videoBufferBytes(false, 2 * gb + 1, 256))
    }

    @Test
    fun aHeapTooSmallForAThirdStillGetsABufferThatPlays() {
        // 16 MB: under it a 60 Mbit/s file has less than 2 s to play from. Half of a 32 MB heap, the smallest there is.
        assertEquals(16, megabytes(heap = 32, ramGb = 1))
        assertEquals(16, megabytes(heap = 40, ramGb = 1))
    }
}

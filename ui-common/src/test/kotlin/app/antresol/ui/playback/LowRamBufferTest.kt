package app.antresol.ui.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only a low-RAM device gets a smaller video buffer than ExoPlayer's default. */
class LowRamBufferTest {
    private val mb = 1024 * 1024

    private val gb = 1024L * 1024 * 1024

    @Test
    fun ordinaryDevicesKeepTheDefaultWhateverTheirHeap() {
        assertNull(lowRamBufferBytes(lowRamDevice = false, totalRamBytes = 4 * gb, memoryClassMb = 256))
        // A 3–4 GB phone or TV box often reports a 192 MB (or smaller) heap: not low on memory.
        assertNull(lowRamBufferBytes(lowRamDevice = false, totalRamBytes = 3 * gb, memoryClassMb = 192))
        assertNull(lowRamBufferBytes(lowRamDevice = false, totalRamBytes = 2 * gb + 1, memoryClassMb = 128))
    }

    @Test
    fun aDeviceWithTwoGigabytesOrLessGetsAThirdOfTheHeap() {
        assertEquals(64 * mb, lowRamBufferBytes(lowRamDevice = false, totalRamBytes = 2 * gb, memoryClassMb = 192))
        assertEquals(42 * mb, lowRamBufferBytes(lowRamDevice = false, totalRamBytes = 2 * gb - 200 * mb, memoryClassMb = 128))
        // Never under 32 MB: 13 s of a 20 Mbit/s file.
        assertEquals(32 * mb, lowRamBufferBytes(lowRamDevice = false, totalRamBytes = gb, memoryClassMb = 48))
    }

    @Test
    fun aLowRamDeviceIsCappedWhateverItsMemory() {
        assertEquals(64 * mb, lowRamBufferBytes(lowRamDevice = true, totalRamBytes = 4 * gb, memoryClassMb = 384))
        assertEquals(32 * mb, lowRamBufferBytes(lowRamDevice = true, totalRamBytes = 4 * gb, memoryClassMb = 96))
    }
}

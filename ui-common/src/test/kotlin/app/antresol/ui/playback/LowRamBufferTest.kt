package app.antresol.ui.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only a low-RAM device gets a smaller video buffer than ExoPlayer's default. */
class LowRamBufferTest {
    private val mb = 1024 * 1024

    @Test
    fun ordinaryDevicesKeepTheDefault() {
        assertNull(lowRamBufferBytes(lowRamDevice = false, memoryClassMb = 256))
        assertNull(lowRamBufferBytes(lowRamDevice = false, memoryClassMb = 512))
    }

    @Test
    fun smallHeapsGetAQuarterOfTheHeap() {
        assertEquals(48 * mb, lowRamBufferBytes(lowRamDevice = false, memoryClassMb = 192))
        assertEquals(32 * mb, lowRamBufferBytes(lowRamDevice = false, memoryClassMb = 128))
        assertEquals(16 * mb, lowRamBufferBytes(lowRamDevice = false, memoryClassMb = 48))
    }

    @Test
    fun aLowRamDeviceIsCappedWhateverItsHeap() {
        assertEquals(64 * mb, lowRamBufferBytes(lowRamDevice = true, memoryClassMb = 384))
        assertEquals(24 * mb, lowRamBufferBytes(lowRamDevice = true, memoryClassMb = 96))
    }
}

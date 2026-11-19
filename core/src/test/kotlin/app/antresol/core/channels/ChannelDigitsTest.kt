package app.antresol.core.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelDigitsTest {
    @Test
    fun digitsBuildANumberShownWithACursor() {
        var t = ""
        t = ChannelDigits.add(t, 2)
        assertEquals("2_", ChannelDigits.display(t))
        t = ChannelDigits.add(t, 3)
        assertEquals("23_", ChannelDigits.display(t))
        assertEquals(23, ChannelDigits.number(t))
        // A leading zero is dropped; a fifth digit starts again.
        assertEquals("", ChannelDigits.add("", 0))
        assertEquals("7", ChannelDigits.add("1234", 7))
        assertEquals("1234", ChannelDigits.display("1234"))
        assertNull(ChannelDigits.number(""))
    }

    @Test
    fun remoteKeysAreDigits() {
        assertEquals(0, ChannelDigits.digitOf(7))
        assertEquals(9, ChannelDigits.digitOf(16))
        assertEquals(5, ChannelDigits.digitOf(149))
        assertNull(ChannelDigits.digitOf(23))
    }
}

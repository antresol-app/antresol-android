package app.antresol.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebVttTest {
    private val vtt = """
        WEBVTT

        NOTE kept

        1
        00:00:01.000 --> 00:00:03.500
        Before the start

        00:00:09.000 --> 00:00:12.250 line:90%
        Straddles

        01:00:20.5 --> 01:00:22.000
        Late
    """.trimIndent()

    @Test
    fun shiftsDropsAndClampsCues() {
        val shifted = WebVtt.shift(vtt, -10_000)
        val expected = """
            WEBVTT

            NOTE kept

            00:00:00.000 --> 00:00:02.250 line:90%
            Straddles

            01:00:10.500 --> 01:00:12.000
            Late
        """.trimIndent()
        assertEquals(expected, shifted)
    }

    @Test
    fun zeroOffsetKeepsTextUntouched() {
        assertEquals(vtt, WebVtt.shift(vtt, 0))
    }

    @Test
    fun parsesShortAndCommaTimestamps() {
        assertEquals(65_250L, WebVtt.parseTimestamp("01:05.25"))
        assertEquals(3_723_004L, WebVtt.parseTimestamp("01:02:03,004"))
        assertNull(WebVtt.parseTimestamp("garbage"))
        assertEquals("01:02:03.004", WebVtt.formatTimestamp(3_723_004))
    }
}

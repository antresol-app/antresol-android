package app.antresol.ui.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player clocks run only while they are switched on: no wake-ups while paused or in the background. */
@OptIn(ExperimentalCoroutinesApi::class)
class TickerTest {
    @Test
    fun ticksAtOnceThenEveryPeriodWhileOn() = runTest {
        var ticks = 0
        val ticker = Ticker(backgroundScope, 500) { ticks++ }
        assertFalse(ticker.running)
        ticker.set(true)
        runCurrent()
        assertEquals(1, ticks)
        advanceTimeBy(1_600)
        assertEquals(4, ticks)
        assertTrue(ticker.running)
    }

    @Test
    fun doesNotTickAtAllOnceOff() = runTest {
        var ticks = 0
        val ticker = Ticker(backgroundScope, 500) { ticks++ }
        ticker.set(true)
        advanceTimeBy(1_100)
        val before = ticks
        ticker.set(false)
        assertFalse(ticker.running)
        advanceTimeBy(60 * 60 * 1_000L)
        assertEquals(before, ticks)
    }

    @Test
    fun switchingOnTwiceKeepsOneLoopAndItCanStartAgain() = runTest {
        var ticks = 0
        val ticker = Ticker(backgroundScope, 500) { ticks++ }
        ticker.set(true)
        ticker.set(true)
        runCurrent()
        assertEquals(1, ticks)
        ticker.set(false)
        ticker.set(false)
        ticker.set(true)
        runCurrent()
        assertEquals(2, ticks)
        advanceTimeBy(501)
        assertEquals(3, ticks)
    }
}

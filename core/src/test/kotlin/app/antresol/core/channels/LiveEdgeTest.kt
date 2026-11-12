package app.antresol.core.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEdgeTest {
    private fun clock(position: Long, edge: Long = 100_000, start: Long = edge - 60_000) = LiveClock(true, position, edge, start)

    @Test
    fun goLiveShowsOnlyMoreThanTenSecondsBehind() {
        assertFalse(LiveEdge.showGoLive(clock(100_000)))
        assertFalse(LiveEdge.showGoLive(clock(90_000)))
        assertTrue(LiveEdge.showGoLive(clock(89_500)))
        assertEquals(30_000L, LiveEdge.behind(clock(70_000)))
        // A little ahead of the edge (catching up) is live.
        assertEquals(0L, LiveEdge.behind(clock(103_000)))
    }

    @Test
    fun aStreamThatIsNotLiveHasNoEdge() {
        val vod = LiveClock(live = false, positionMs = 5_000, edgeMs = 900_000)
        assertFalse(LiveEdge.showGoLive(vod))
        assertEquals(0L, LiveEdge.behind(vod))
        assertFalse(LiveEdge.resumeAtEdge(own = false, pausedMs = 3_600_000, behindAtPauseMs = 0, clock = vod))
    }

    @Test
    fun ownChannelResumesInPlaceWithinTheMinuteElseAtTheEdge() {
        assertFalse(LiveEdge.resumeAtEdge(own = true, pausedMs = 20_000, behindAtPauseMs = 0, clock = clock(80_000)))
        assertFalse(LiveEdge.resumeAtEdge(own = true, pausedMs = 45_000, behindAtPauseMs = 10_000, clock = clock(45_000)))
        assertTrue(LiveEdge.resumeAtEdge(own = true, pausedMs = 60_000, behindAtPauseMs = 0, clock = clock(40_000)))
        assertTrue(LiveEdge.resumeAtEdge(own = true, pausedMs = 45_000, behindAtPauseMs = 20_000, clock = clock(45_000)))
        assertTrue(LiveEdge.resumeAtEdge(own = true, pausedMs = 300_000, behindAtPauseMs = 0, clock = clock(45_000)))
    }

    @Test
    fun externalChannelUsesItsSeekableWindow() {
        // A provider keeping two hours: a ten-minute pause comes back where it stopped.
        val dvr = clock(position = 400_000, edge = 7_200_000, start = 0)
        assertEquals(7_200_000L, LiveEdge.window(own = false, clock = dvr))
        assertFalse(LiveEdge.resumeAtEdge(own = false, pausedMs = 600_000, behindAtPauseMs = 0, clock = dvr))
        // A provider keeping 30 s: a 40 s pause resumes at the edge.
        assertTrue(LiveEdge.resumeAtEdge(own = false, pausedMs = 40_000, behindAtPauseMs = 0, clock = clock(90_000, 100_000, 70_000)))
    }

    @Test
    fun fallenOutOfTheStreamResumesAtTheEdge() {
        assertTrue(LiveEdge.resumeAtEdge(own = false, pausedMs = 1_000, behindAtPauseMs = 0, clock = clock(40_000, 100_000, 50_000)))
    }

    @Test
    fun centrePlayPauseShowsWithTheControlsAndWhilePaused() {
        assertTrue(LiveEdge.centerShown(controls = true, paused = false, ready = true))
        assertFalse(LiveEdge.centerShown(controls = false, paused = false, ready = true))
        assertTrue(LiveEdge.centerShown(controls = false, paused = true, ready = true))
        // Not before the channel plays: the spinner has the centre.
        assertFalse(LiveEdge.centerShown(controls = true, paused = false, ready = false))
        assertEquals(4_000L, LiveEdge.CONTROLS_HIDE_MS)
    }
}

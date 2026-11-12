package app.antresol.core.channels

import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.ChannelEpgRow
import app.antresol.core.model.ChannelProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class EpgTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun p(id: String, start: String, end: String) = ChannelProgram(id = id, start = "2026-11-11T${start}:00Z", end = "2026-11-11T${end}:00Z")

    private val row = listOf(p("a", "20:00", "21:30"), p("b", "21:30", "22:10"), p("c", "22:40", "23:00"))

    @Test
    fun theWindowStartsThreeHoursBeforeTheHalfHour() {
        assertEquals(ms("2026-11-11T18:30:00Z"), Epg.origin(ms("2026-11-11T21:44:10Z")))
        assertEquals(ms("2026-11-13T20:30:00Z"), Epg.limit(ms("2026-11-11T21:44:10Z")))
    }

    @Test
    fun verticalMovesKeepTheTimePosition() {
        assertEquals("a", Epg.landing(row, ms("2026-11-11T21:00:00Z"))?.id)
        assertEquals("b", Epg.landing(row, ms("2026-11-11T21:30:00Z"))?.id)
        // A gap: the nearest programme.
        assertEquals("c", Epg.landing(row, ms("2026-11-11T22:35:00Z"))?.id)
        assertEquals("b", Epg.landing(row, ms("2026-11-11T22:12:00Z"))?.id)
        assertNull(Epg.landing(emptyList(), 0))
        // A programme that started off-screen keeps the visible edge as the position.
        assertEquals(ms("2026-11-11T20:30:00Z"), Epg.anchorFor(row[0], ms("2026-11-11T20:30:00Z")))
        assertEquals(ms("2026-11-11T21:30:00Z"), Epg.anchorFor(row[1], ms("2026-11-11T20:30:00Z")))
    }

    @Test
    fun pagesMergeWithoutDuplicates() {
        val first = listOf(ChannelEpgRow(1, programs = row.take(2)), ChannelEpgRow(2))
        val page = ChannelEpg(channels = listOf(ChannelEpgRow(2, name = "Two"), ChannelEpgRow(1, programs = row.drop(1))))
        val merged = Epg.merge(first, page)
        assertEquals(listOf(2L, 1L), merged.map { it.id })
        assertEquals(listOf("a", "b", "c"), merged[1].programs.map { it.id })
        assertEquals("Two", merged[0].name)
    }

    @Test
    fun visibleProgrammesOverlapTheWindow() {
        val visible = Epg.visible(ChannelEpgRow(1, programs = row), ms("2026-11-11T21:30:00Z"), ms("2026-11-11T22:40:00Z"))
        assertEquals(listOf("b"), visible.map { it.id })
    }

    @Test
    fun theScaleFollowsTheTypicalProgramme() {
        val films = listOf(ChannelEpgRow(1, programs = listOf(p("f", "20:00", "21:30"), p("g", "21:30", "23:00"))))
        val clips = listOf(ChannelEpgRow(1, programs = listOf(p("a", "20:00", "20:06"), p("b", "20:06", "20:12"), p("c", "20:12", "20:19"))))
        assertEquals(4f, Epg.minuteWidth(films, 140f, 4f, 24f))
        assertEquals(140f / 6f, Epg.minuteWidth(clips, 140f, 4f, 24f), 0.01f)
        assertEquals(30 * 60_000L, Epg.tickMs(4f, 90f))
        assertEquals(5 * 60_000L, Epg.tickMs(23f, 90f))
        assertEquals(30f, Epg.typicalMinutes(emptyList()))
    }

    @Test
    fun stickyLabelsPinToTheVisibleEdge() {
        val min = 60_000L
        // Starts in view: no offset.
        assertEquals(0L, Epg.stickyLabelOffsetMs(1_000_000, 2_000_000, 900_000))
        // Started 5 min before the edge, plenty left: pinned to the edge.
        assertEquals(5 * min, Epg.stickyLabelOffsetMs(0, 60 * min, 5 * min))
        // Near its end: still pinned to the edge (the title shortens), never left of it, so every
        // row's label keeps the same inset (round 4: short blocks slid under the channel column).
        assertEquals(58 * min, Epg.stickyLabelOffsetMs(0, 60 * min, 58 * min))
        assertEquals(5 * min, Epg.stickyLabelOffsetMs(0, 8 * min, 5 * min))
        // Ended before the edge: at most the block's length.
        assertEquals(8 * min, Epg.stickyLabelOffsetMs(0, 8 * min, 9 * min))
    }
}

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
}

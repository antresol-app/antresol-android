package app.antresol.core.channels

import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.ChannelEpgRow
import app.antresol.core.model.ChannelProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun aProgrammeAcrossAPageEdgeIsOneBlock() {
        fun film(id: String, start: String, end: String, item: Long = 7) = p(id, start, end).copy(itemId = item)
        fun merged(first: List<ChannelProgram>, second: List<ChannelProgram>) = Epg.merge(
            Epg.merge(emptyList(), ChannelEpg(channels = listOf(ChannelEpgRow(1, programs = first)))),
            ChannelEpg(channels = listOf(ChannelEpgRow(1, programs = second))),
        )[0].programs.map { Triple(it.id, it.start.substring(11, 16), it.end.substring(11, 16)) }

        // Sent again by the next page, with or without an id: once.
        assertEquals(listOf(Triple("a", "20:00", "21:30")), merged(listOf(p("a", "20:00", "21:30")), listOf(p("a", "20:00", "21:30"))))
        assertEquals(listOf(Triple("", "20:00", "21:30")), merged(listOf(p("", "20:00", "21:30")), listOf(p("", "20:00", "21:30"))))
        // A film in two parts, the page's edge (21:00) in its first part: the next page has it whole.
        assertEquals(
            listOf(Triple("1-4", "20:10", "21:50"), Triple("1-6", "21:50", "22:30")),
            merged(listOf(film("1-4", "20:10", "21:00")), listOf(film("1-4", "20:10", "21:50"), film("1-6", "21:50", "22:30", item = 8))),
        )
        // The edge in its second part: the next page has only that part, under its own id and start.
        assertEquals(
            listOf(Triple("1-3", "19:00", "20:00"), Triple("1-4", "20:10", "22:20")),
            merged(
                listOf(film("1-3", "19:00", "20:00", item = 6), film("1-4", "20:10", "21:50")),
                listOf(film("1-5", "21:00", "22:20")),
            ),
        )
        // The same start under another id (the schedule was rebuilt between the pages): one block.
        assertEquals(listOf(Triple("1-9", "20:00", "21:30")), merged(listOf(film("1-4", "20:00", "21:00")), listOf(film("1-9", "20:00", "21:30", item = 8))))
        // A repeat of the title right after it, and programmes without an item, stay apart.
        assertEquals(
            listOf(Triple("1-4", "20:00", "21:00"), Triple("1-5", "21:00", "22:00")),
            merged(listOf(film("1-4", "20:00", "21:00")), listOf(film("1-5", "21:00", "22:00"))),
        )
        assertEquals(2, merged(listOf(p("x1", "20:00", "21:00")), listOf(p("x2", "20:30", "21:30"))).size)
    }

    private fun film(id: String, start: String, end: String, item: Long = 7, title: String = "") = p(id, start, end).copy(itemId = item, title = title)

    /** A page as the server answers it: its window `[from, to)` and one channel's programmes. */
    private fun page(from: String, to: String, vararg programs: ChannelProgram) =
        ChannelEpg(from = "2026-11-11T$from:00Z", to = "2026-11-11T$to:00Z", channels = listOf(ChannelEpgRow(1, programs = programs.toList())))

    private fun merged(vararg pages: ChannelEpg): List<ChannelProgram> {
        val programs = pages.fold(emptyList<ChannelEpgRow>()) { rows, page -> Epg.merge(rows, page) }[0].programs
        // Whatever the pages hold: a row never has two programmes with one key (the blocks and the TV's focus go by it).
        assertEquals(programs.map(Epg::key), programs.map(Epg::key).distinct())
        return programs
    }

    private fun List<ChannelProgram>.times() = map { Triple(it.id, it.start.substring(11, 16), it.end.substring(11, 16)) }

    @Test
    fun aScheduleRebuiltBetweenTwoPagesIsShownAsTheNewerPageHasIt() {
        // The server rebuilds the schedule from an entry on and numbers the new entries from there:
        // the id of the programme across the edge (22:00) is now a shorter programme of another title.
        val rebuilt = merged(
            page("16:00", "22:00", film("c-50", "19:00", "21:00", item = 5), film("c-51", "21:00", "23:30", item = 7, title = "Old")),
            page("22:00", "23:59", film("c-51", "21:00", "22:40", item = 8, title = "New"), film("c-52", "22:40", "23:20", item = 9)),
        )
        assertEquals(listOf(Triple("c-50", "19:00", "21:00"), Triple("c-51", "21:00", "22:40"), Triple("c-52", "22:40", "23:20")), rebuilt.times())
        assertEquals("New", rebuilt[1].title)
        // The same start under another id: the newer page's, also when it is the shorter one.
        assertEquals(
            listOf(Triple("c-60", "21:00", "22:10")),
            merged(page("16:00", "22:00", film("c-51", "21:00", "23:30")), page("22:00", "23:59", film("c-60", "21:00", "22:10", item = 8))).times(),
        )
    }

    @Test
    fun aTitleMovedByARebuildIsNotJoinedWithItsOldPlace() {
        // 20:00–22:00 before the rebuild, 21:00–23:00 after it: not one film of three hours.
        val moved = merged(
            page("15:00", "21:00", film("c-51", "20:00", "22:00")),
            page("21:00", "23:59", film("c-53", "21:00", "23:00")),
        )
        assertTrue(moved.none { it.endMs!! - it.startMs!! > 2 * Epg.HOUR_MS })
        assertEquals(listOf(Triple("c-51", "20:00", "22:00"), Triple("c-53", "22:00", "23:00")), moved.times())
        // What the older rows have inside the newer page's window is the newer page's to tell.
        val replaced = merged(
            page("15:00", "23:59", film("c-51", "20:00", "22:00"), film("c-52", "22:00", "23:00", item = 8)),
            page("21:00", "23:59", film("c-53", "21:30", "23:30")),
        )
        assertEquals(listOf(Triple("c-51", "20:00", "22:00"), Triple("c-53", "22:00", "23:30")), replaced.times())
    }

    @Test
    fun anIdUsedAgainAfterARebuildIsOneProgrammeInTheRow() {
        // The older c-52 goes (one key, one block); the programme across the edge ends where the newer page's begins.
        val row = merged(
            page("17:00", "23:00", film("c-51", "21:00", "21:30", item = 1), film("c-52", "21:30", "22:00", item = 2), film("c-53", "22:00", "23:10", item = 3)),
            page("23:00", "23:59", film("c-52", "22:50", "23:50", item = 4)),
        )
        assertEquals(listOf(Triple("c-51", "21:00", "21:30"), Triple("c-53", "22:00", "22:50"), Triple("c-52", "22:50", "23:50")), row.times())
    }

    @Test
    fun aFilmShownThreeTimesInARowIsNotJoinedAcrossItsShowings() {
        // Two files of 50 min, three showings from 18:00. The server joins a film's files by its first
        // file IN THE PAGE: a page that begins in the second file of showing 2 (20:30) answers that
        // file joined with the first file of showing 3, and then showing 3's second file alone.
        val showings = merged(
            page("14:30", "20:30", film("1-1", "18:00", "19:40"), film("1-3", "19:40", "21:20")),
            page("20:30", "23:59", film("1-4", "20:30", "22:10"), film("1-6", "22:10", "23:00"), film("1-7", "23:00", "23:50", item = 8)),
        )
        // Showing 2 keeps its end; showing 3 is two blocks (its files), since nothing says they are one.
        assertEquals(
            listOf(Triple("1-1", "18:00", "19:40"), Triple("1-3", "19:40", "21:20"), Triple("1-4", "21:20", "22:10"), Triple("1-6", "22:10", "23:00"), Triple("1-7", "23:00", "23:50")),
            showings.times(),
        )
        // Merging the page again changes nothing.
        val again = merged(
            page("14:30", "20:30", film("1-1", "18:00", "19:40"), film("1-3", "19:40", "21:20")),
            page("20:30", "23:59", film("1-4", "20:30", "22:10"), film("1-6", "22:10", "23:00"), film("1-7", "23:00", "23:50", item = 8)),
            page("20:30", "23:59", film("1-4", "20:30", "22:10"), film("1-6", "22:10", "23:00"), film("1-7", "23:00", "23:50", item = 8)),
        )
        assertEquals(showings.times(), again.times())
    }

    @Test
    fun visibleProgrammesOverlapTheWindow() {
        val visible = Epg.visible(ChannelEpgRow(1, programs = row), ms("2026-11-11T21:30:00Z"), ms("2026-11-11T22:40:00Z"))
        assertEquals(listOf("b"), visible.map { it.id })
    }

    @Test
    fun programmeTimesAreParsedOnTheProgramme() {
        val program = p("a", "20:00", "21:30")
        assertEquals(ms("2026-11-11T20:00:00Z"), program.startMs)
        assertEquals(ms("2026-11-11T21:30:00Z"), program.endMs)
        assertEquals(null, ChannelProgram(id = "x", start = "soon", end = "").startMs)
        assertEquals(null, ChannelProgram(id = "x", start = "soon", end = "").endMs)
        // A copy with other times reads its own.
        assertEquals(ms("2026-11-11T22:00:00Z"), program.copy(start = "2026-11-11T22:00:00Z").startMs)
    }

    @Test
    fun theComposedWindowCoversTheViewAndAScreenOnEachSide() {
        val hour = Epg.HOUR_MS
        val left = ms("2026-11-11T20:20:00Z")
        val window = Epg.composedWindow(left, viewMs = 2 * hour)
        assertEquals(ms("2026-11-11T18:00:00Z"), window.first)
        assertEquals(ms("2026-11-11T01:00:00Z") + 24 * hour, window.last)
        // The same window while the guide scrolls within the hour, so the rows are not recomposed by every frame.
        assertEquals(window, Epg.composedWindow(ms("2026-11-11T20:00:00Z"), 2 * hour))
        assertEquals(window, Epg.composedWindow(ms("2026-11-11T20:59:59Z"), 2 * hour))
        // Everything in view, and a view more on each side, is inside wherever the edge is in the hour.
        for (edge in listOf(left, ms("2026-11-11T20:00:00Z"), ms("2026-11-11T20:59:59Z"))) {
            assertTrue(window.first <= edge - 2 * hour && window.last >= edge + 4 * hour)
        }
        // Before the first layout (nothing in view yet) there is still an hour on each side.
        val unmeasured = Epg.composedWindow(left, viewMs = 0)
        assertEquals(ms("2026-11-11T19:00:00Z"), unmeasured.first)
        assertEquals(ms("2026-11-11T22:00:00Z"), unmeasured.last)
    }

    @Test
    fun overlappingKeepsTheProgrammesOfAWindow() {
        val programs = listOf(p("a", "18:00", "19:00"), p("b", "19:00", "21:00"), p("c", "21:00", "22:00"), ChannelProgram(id = "bad"))
        val window = ms("2026-11-11T19:30:00Z") to ms("2026-11-11T21:00:00Z")
        assertEquals(listOf("b"), Epg.overlapping(programs, window.first, window.second).map { it.id })
        assertEquals(listOf("a", "b", "c"), Epg.overlapping(programs, ms("2026-11-11T18:59:00Z"), ms("2026-11-11T21:01:00Z")).map { it.id })
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

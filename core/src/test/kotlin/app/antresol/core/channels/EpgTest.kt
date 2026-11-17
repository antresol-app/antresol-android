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

    // ---- The merge of guide pages, as the app asks for them: forward only, each window starting where the one before ended. ----

    /** One schedule entry of the server (a file of a title), in minutes of the day. */
    private data class Entry(val seq: Int, val item: Long, val file: Long, val start: Int, val end: Int)

    private fun at(minute: Int): String = Instant.parse("2026-11-11T00:00:00Z").plusSeconds(minute * 60L).toString()

    private fun hm(text: String) = text.substringBefore(':').toInt() * 60 + text.substringAfter(':').toInt()

    /** Entries back to back from [start]: `item to minutes` is a title in one file, `item to listOf(…)` one in several. */
    private fun schedule(start: String, vararg titles: Pair<Long, Any>, firstSeq: Int = 1): List<Entry> {
        var at = hm(start)
        var seq = firstSeq
        val out = ArrayList<Entry>()
        for ((item, lengths) in titles) {
            val files = if (lengths is List<*>) lengths.map { it as Int } else listOf(lengths as Int)
            files.forEachIndexed { index, length ->
                out += Entry(seq++, item, item * 10 + index, at, at + length)
                at += length
            }
        }
        return out
    }

    /**
     * The server's answer for `[from, to)` (minutes), a copy of its `Store.Range` + `programs()`: the
     * entries that overlap the window, a title's files joined while they are back to back and the
     * first file of the programme does not come again, under the id of the first entry IN THE WINDOW.
     */
    private fun answer(entries: List<Entry>, from: Int, to: Int): ChannelEpg {
        val inWindow = entries.filter { it.end > from && it.start < to }
        val programs = ArrayList<ChannelProgram>()
        var i = 0
        while (i < inWindow.size) {
            val first = inWindow[i]
            var end = first.end
            while (i + 1 < inWindow.size && inWindow[i + 1].item == first.item && inWindow[i + 1].file != first.file && inWindow[i + 1].start == end) {
                i++
                end = inWindow[i].end
            }
            i++
            programs += ChannelProgram(id = "c-${first.seq}", itemId = first.item, kind = "movie", start = at(first.start), end = at(end))
        }
        return ChannelEpg(from = at(from), to = at(to), channels = listOf(ChannelEpgRow(1, programs = programs)))
    }

    private fun answer(entries: List<Entry>, from: String, to: String) = answer(entries, hm(from), hm(to))

    private fun film(id: String, start: String, end: String, item: Long = 7, title: String = "") = p(id, start, end).copy(itemId = item, title = title)

    private fun external(start: String, end: String) = p("x1-$start", start, end).copy(kind = ChannelProgram.KIND_EXTERNAL)

    /** A page with the window `[from, to)` and one channel's programmes. */
    private fun page(from: String, to: String, vararg programs: ChannelProgram, external: Boolean = false) = ChannelEpg(
        from = "2026-11-11T$from:00Z",
        to = "2026-11-11T$to:00Z",
        channels = listOf(ChannelEpgRow(1, programs = programs.toList(), external = external)),
    )

    /** What holds after any merge: by start, one programme per key; on a library channel no two at once. */
    private fun checked(programs: List<ChannelProgram>, library: Boolean = true): List<ChannelProgram> {
        assertEquals("keys", programs.map(Epg::key).distinct(), programs.map(Epg::key))
        assertEquals("order", programs.sortedBy { it.startMs }, programs)
        programs.forEach { assertTrue("a programme has a length: $it", it.endMs!! > it.startMs!!) }
        if (library) programs.zipWithNext { a, b -> assertTrue("overlap: ${a.id} ${a.end} / ${b.id} ${b.start}", a.endMs!! <= b.startMs!!) }
        return programs
    }

    /** The pages merged one after another, as the guide does; the windows must be adjacent. */
    private fun merged(vararg pages: ChannelEpg, library: Boolean = true): List<ChannelProgram> {
        pages.toList().zipWithNext { a, b -> assertEquals("the app pages forward: a window starts where the one before ended", a.to, b.from) }
        var rows = emptyList<ChannelEpgRow>()
        for (page in pages) rows = Epg.merge(rows, page).also { checked(it[0].programs, library) }
        return rows[0].programs
    }

    private fun List<ChannelProgram>.times() = map { Triple(it.id, it.start.substring(11, 16), it.end.substring(11, 16)) }

    private fun block(id: String, start: String, end: String) = Triple(id, start, end)

    @Test
    fun consistentPagesGiveWhatOneWindowWouldGive() {
        // Single-file titles and a film in two files (20:10–21:00–21:50); nothing changes between the pages.
        val entries = schedule("18:00", 1L to 70, 2L to 60, 7L to listOf(50, 50), 3L to 40, 4L to 90, 5L to 60)
        val whole = answer(entries, "17:00", "23:59").channels[0].programs.times()
        // A seam inside a single-file title, inside the film's first file, inside its second file, at a programme's start.
        for (seam in listOf("18:30", "19:10", "19:40", "20:30", "21:30", "21:50", "22:30")) {
            val pages = merged(answer(entries, "17:00", seam), answer(entries, seam, "23:59"))
            // The ids are those of the first page that had the programme; the blocks are the schedule's.
            assertEquals("seam $seam", whole.map { it.second to it.third }, pages.times().map { it.second to it.third })
        }
        // Three pages.
        assertEquals(whole, merged(answer(entries, "17:00", "19:00"), answer(entries, "19:00", "21:30"), answer(entries, "21:30", "23:59")).times())
    }

    @Test
    fun aSeamInAFilmsFirstFileTakesThePagesWholeCopy() {
        // Page 1 has only file 1 (file 2 starts after its window); page 2 has the film whole, same id and start.
        val entries = schedule("20:10", 7L to listOf(50, 50), 8L to 40, firstSeq = 4)
        assertEquals(listOf(block("c-4", "20:10", "21:00")), answer(entries, "15:00", "20:30").channels[0].programs.times())
        assertEquals(
            listOf(block("c-4", "20:10", "21:50"), block("c-6", "21:50", "22:30")),
            merged(answer(entries, "15:00", "20:30"), answer(entries, "20:30", "23:59")).times(),
        )
    }

    @Test
    fun aSeamInAFilmsLaterFileIsOneBlockUnderTheFirstId() {
        // Page 2 has only the second file, under its own id and start.
        val two = schedule("20:10", 7L to listOf(50, 50), 8L to 40, firstSeq = 4)
        assertEquals(block("c-5", "21:00", "21:50"), answer(two, "21:20", "23:59").channels[0].programs.times()[0])
        assertEquals(
            listOf(block("c-4", "20:10", "21:50"), block("c-6", "21:50", "22:30")),
            merged(answer(two, "15:00", "21:20"), answer(two, "21:20", "23:59")).times(),
        )
        // Three files of 90 min, the seam in the second: page 1 has files 1+2, page 2 files 2+3, both
        // three hours long. One film, 20:00–00:30 (E-4a).
        val three = schedule("20:00", 7L to listOf(90, 90, 90), 8L to 30, firstSeq = 10)
        val film = merged(answer(three, hm("16:00"), hm("22:00")), answer(three, hm("22:00"), hm("28:00")))
        assertEquals(listOf("c-10", "c-13"), film.map { it.id })
        assertEquals(at(hm("20:00")) to at(hm("24:30")), film[0].start to film[0].end)
        // A page that ends inside the film (never in the app: a page is 6 h) cannot tell whether the
        // title goes on as a repeat: two blocks of the title, cut where file 3 starts, no hole.
        val short = merged(answer(three, hm("16:00"), hm("22:00")), answer(three, hm("22:00"), hm("23:30")), answer(three, hm("23:30"), hm("28:00")))
        assertEquals(listOf(at(hm("20:00")), at(hm("23:00")), at(hm("24:30"))), short.map { it.start })
        assertEquals(listOf(7L, 7L, 8L), short.map { it.itemId })
    }

    @Test
    fun aSeamExactlyBetweenTwoFilesGivesTwoBlocksOfTheTitle() {
        // Nothing tells file 2 at the seam from a repeat of a single-file title: two blocks, none invented.
        val entries = schedule("20:00", 7L to listOf(60, 60), firstSeq = 4)
        assertEquals(
            listOf(block("c-4", "20:00", "21:00"), block("c-5", "21:00", "22:00")),
            merged(answer(entries, "15:00", "21:00"), answer(entries, "21:00", "23:59")).times(),
        )
        val repeat = schedule("20:00", 7L to 60, 7L to 60, firstSeq = 4)
        assertEquals(
            listOf(block("c-4", "20:00", "21:00"), block("c-5", "21:00", "22:00")),
            merged(answer(repeat, "15:00", "21:00"), answer(repeat, "21:00", "23:59")).times(),
        )
        // The repeat of a single-file title with the seam inside either showing.
        for (seam in listOf("20:30", "21:30")) {
            assertEquals(
                listOf(block("c-4", "20:00", "21:00"), block("c-5", "21:00", "22:00")),
                merged(answer(repeat, "15:00", seam), answer(repeat, seam, "23:59")).times(),
            )
        }
    }

    @Test
    fun aRebuildBetweenTwoPagesTheNewerPageIsShownWholeWithNoHole() {
        // The review's trace (E-1): seam 23:00, the schedule rebuilt from 21:30 on. The newer page's
        // programme across the seam starts BEFORE two programmes the rows have.
        val rebuilt = merged(
            page("17:00", "23:00", film("c-51", "21:00", "21:30", item = 1), film("c-52", "21:30", "22:00", item = 2), film("c-53", "22:00", "22:30", item = 3), film("c-54", "22:30", "23:10", item = 4)),
            page("23:00", "23:59", film("c-54", "21:50", "23:40", item = 9, title = "New"), film("c-55", "23:40", "23:55", item = 10)),
        )
        assertEquals(
            listOf(block("c-51", "21:00", "21:30"), block("c-52", "21:30", "21:50"), block("c-54", "21:50", "23:40"), block("c-55", "23:40", "23:55")),
            rebuilt.times(),
        )
        assertEquals("New", rebuilt[2].title)
        // The id of the programme across the seam is another, shorter title's now: the page's copy.
        val reused = merged(
            page("16:00", "22:00", film("c-50", "19:00", "21:00", item = 5), film("c-51", "21:00", "23:30", item = 7, title = "Old")),
            page("22:00", "23:59", film("c-51", "21:00", "22:40", item = 8, title = "New"), film("c-52", "22:40", "23:20", item = 9)),
        )
        assertEquals(listOf(block("c-50", "19:00", "21:00"), block("c-51", "21:00", "22:40"), block("c-52", "22:40", "23:20")), reused.times())
        assertEquals("New", reused[1].title)
        // The same start under another id, the page's copy shorter: the page's.
        assertEquals(
            listOf(block("c-60", "21:00", "22:10")),
            merged(page("16:00", "22:00", film("c-51", "21:00", "23:30")), page("22:00", "23:59", film("c-60", "21:00", "22:10", item = 8))).times(),
        )
        // The page starts later than its window (a gap in the new schedule): what the rows had across
        // the seam ends at the window's start, as the page has nothing of it.
        assertEquals(
            listOf(block("c-51", "21:00", "22:00"), block("c-52", "22:20", "23:00")),
            merged(page("16:00", "22:00", film("c-51", "21:00", "22:40")), page("22:00", "23:59", film("c-52", "22:20", "23:00", item = 8))).times(),
        )
    }

    @Test
    fun anIdUsedAgainAfterARebuildIsOneProgrammeInTheRow() {
        // c-52 is the page's programme now: the older one goes (its stretch before the seam is not
        // known any more: empty until the guide is reloaded); c-53 ends where the page's begins.
        val row = merged(
            page("17:00", "23:00", film("c-51", "21:00", "21:30", item = 1), film("c-52", "21:30", "22:00", item = 2), film("c-53", "22:00", "23:10", item = 3)),
            page("23:00", "23:59", film("c-52", "22:50", "23:50", item = 4)),
        )
        assertEquals(listOf(block("c-51", "21:00", "21:30"), block("c-53", "22:00", "22:50"), block("c-52", "22:50", "23:50")), row.times())
        // The programme across the seam itself has the id the page uses for a later one.
        val across = merged(
            page("17:00", "23:00", film("c-52", "22:00", "23:10", item = 3)),
            page("23:00", "23:59", film("c-51", "22:30", "23:20", item = 5), film("c-52", "23:20", "23:50", item = 4)),
        )
        assertEquals(listOf(block("c-51", "22:30", "23:20"), block("c-52", "23:20", "23:50")), across.times())
    }

    @Test
    fun aTitleMovedByARebuildIsNotJoinedWithItsOldPlace() {
        // 20:00–22:00 before the rebuild, 21:00–23:00 after it, the seam at 21:00 (E-4b). The page
        // starts with it AT its window's start: the entry the rows had across the seam is gone, so
        // this is not its continuation. The newer showing is whole; the stale one ends at the seam.
        val moved = merged(
            page("15:00", "21:00", film("c-51", "20:00", "22:00")),
            page("21:00", "23:59", film("c-53", "21:00", "23:00")),
        )
        assertEquals(listOf(block("c-51", "20:00", "21:00"), block("c-53", "21:00", "23:00")), moved.times())
        // Moved to a start BEFORE the seam it cannot be told from a film in files 20:00/21:00/22:00
        // with the seam in the second (the same answers): one block, as for that film. Known limit.
        val before = merged(
            page("15:30", "21:30", film("c-51", "20:00", "22:00")),
            page("21:30", "23:59", film("c-53", "21:00", "23:00"), film("c-54", "23:00", "23:30", item = 9)),
        )
        assertEquals(listOf(block("c-51", "20:00", "23:00"), block("c-54", "23:00", "23:30")), before.times())
    }

    @Test
    fun aFilmShownTwiceInARowIsNeverABlockLongerThanTheFilm() {
        // Two files of 50 min, two showings from 18:00 (18:00, 18:50 | 19:40, 20:30), then another title.
        val entries = schedule("18:00", 7L to listOf(50, 50), 7L to listOf(50, 50), 8L to 40)
        val showings = listOf(block("c-1", "18:00", "19:40"), block("c-3", "19:40", "21:20"), block("c-5", "21:20", "22:00"))
        assertEquals(showings, answer(entries, "12:00", "23:59").channels[0].programs.times())
        // The seam in a showing's first file, or between the showings: as one window gives it.
        for (seam in listOf("18:20", "19:40", "20:00")) {
            assertEquals("seam $seam", showings, merged(answer(entries, "12:00", seam), answer(entries, seam, "23:59")).times())
        }
        // The seam in the second file of the LAST showing: the page has that file alone.
        assertEquals(showings, merged(answer(entries, "12:00", "21:00"), answer(entries, "21:00", "23:59")).times())
        // The seam in the second file of showing 1: the page answers that file joined with the first
        // file of showing 2 (c-2, 18:50–20:30) and then showing 2's second file alone (c-4). Showing 1
        // keeps its end; showing 2 is its two files as two blocks. Nothing spans two showings.
        assertEquals(
            listOf(block("c-2", "18:50", "20:30"), block("c-4", "20:30", "21:20"), block("c-5", "21:20", "22:00")),
            answer(entries, "19:00", "23:59").channels[0].programs.times(),
        )
        assertEquals(
            listOf(block("c-1", "18:00", "19:40"), block("c-2", "19:40", "20:30"), block("c-4", "20:30", "21:20"), block("c-5", "21:20", "22:00")),
            merged(answer(entries, "12:00", "19:00"), answer(entries, "19:00", "23:59")).times(),
        )
    }

    @Test
    fun aFilmShownThreeTimesInARowIsNeverABlockLongerThanTheFilm() {
        // 18:00, 18:50 | 19:40, 20:30 | 21:20, 22:10 | another title at 23:00.
        val entries = schedule("18:00", 7L to listOf(50, 50), 7L to listOf(50, 50), 7L to listOf(50, 50), 8L to 50)
        // The seam in the second file of showing 2: the page's first programme is that file with the
        // first of showing 3 (c-4, 20:30–22:10), then c-6 and the other title.
        assertEquals(
            listOf(block("c-1", "18:00", "19:40"), block("c-3", "19:40", "21:20"), block("c-4", "21:20", "22:10"), block("c-6", "22:10", "23:00"), block("c-7", "23:00", "23:50")),
            merged(answer(entries, "14:30", "20:40"), answer(entries, "20:40", "23:59")).times(),
        )
        // The seam in the second file of showing 1: the page is cut by second files all along.
        assertEquals(
            listOf(block("c-1", "18:00", "19:40"), block("c-2", "19:40", "20:30"), block("c-4", "20:30", "22:10"), block("c-6", "22:10", "23:00"), block("c-7", "23:00", "23:50")),
            merged(answer(entries, "14:30", "19:00"), answer(entries, "19:00", "23:59")).times(),
        )
        // Two seams, both in second files.
        val pages = merged(answer(entries, "14:30", "19:00"), answer(entries, "19:00", "20:40"), answer(entries, "20:40", "23:59"))
        assertTrue(pages.filter { it.itemId == 7L }.all { it.endMs!! - it.startMs!! <= 100 * 60_000L })
        assertEquals("18:00" to "23:00", pages.first().start.substring(11, 16) to pages.last { it.itemId == 7L }.end.substring(11, 16))
    }

    @Test
    fun externalProgrammesAreReplacedByStartAndNeverJoinedOrCut() {
        // An XMLTV guide may have programmes at once; a page's first one may start before another the rows have.
        val rows = merged(
            page("16:00", "22:00", external("20:00", "21:00"), external("21:00", "22:30"), external("21:30", "22:15"), external = true),
            page("22:00", "23:59", external("21:15", "22:45"), external("22:45", "23:30"), external = true),
            library = false,
        )
        // What starts at or after 21:15 is the page's to tell; what starts before is kept whole.
        assertEquals(listOf("20:00" to "21:00", "21:00" to "22:30", "21:15" to "22:45", "22:45" to "23:30"), rows.times().map { it.second to it.third })
        // The same programme sent again: once.
        val again = merged(
            page("16:00", "22:00", external("21:00", "22:30"), external = true),
            page("22:00", "23:59", external("21:00", "22:30"), external("22:30", "23:00"), external = true),
            library = false,
        )
        assertEquals(listOf("21:00" to "22:30", "22:30" to "23:00"), again.times().map { it.second to it.third })
        // Programmes without an id go by their start.
        val bare = merged(
            page("16:00", "22:00", p("", "21:00", "22:30"), external = true),
            page("22:00", "23:59", p("", "21:00", "22:30"), p("", "22:30", "23:00"), external = true),
            library = false,
        )
        assertEquals(2, bare.size)
    }

    @Test
    fun anEmptyRowInAPageKeepsWhatIsKnown() {
        // The server answers `[]` for a channel whose external guide failed: no information.
        val known = merged(page("16:00", "22:00", film("c-1", "20:00", "21:00"), film("c-2", "21:00", "22:30", item = 8)), page("22:00", "23:59"))
        assertEquals(listOf(block("c-1", "20:00", "21:00"), block("c-2", "21:00", "22:30")), known.times())
        // A channel the rows did not have, and one the page does not have any more.
        val rows = Epg.merge(listOf(ChannelEpgRow(1, programs = row)), ChannelEpg(channels = listOf(ChannelEpgRow(2, programs = row.take(1)))))
        assertEquals(listOf(2L), rows.map { it.id })
        assertEquals(listOf("a"), rows[0].programs.map { it.id })
    }

    @Test
    fun aRowNeverHasTwoProgrammesWithOneKey() {
        // A page that gives a key twice (no server does): the later one.
        val twice = Epg.mergedRow(emptyList(), listOf(film("c-1", "20:00", "21:00"), film("c-1", "21:00", "22:00", item = 8)), from = null, library = true)
        assertEquals(listOf(block("c-1", "21:00", "22:00")), twice.times())
        // A programme whose start cannot be read has no place in the grid.
        val unread = Epg.mergedRow(emptyList(), listOf(ChannelProgram(id = "x", start = "soon"), film("c-1", "20:00", "21:00")), from = null, library = true)
        assertEquals(listOf("c-1"), unread.map { it.id })
        // Without the page's window (an old server): the page tells from its first programme on.
        val unknown = Epg.mergedRow(listOf(film("c-1", "20:00", "21:30"), film("c-2", "21:30", "22:00", item = 8)), listOf(film("c-3", "21:00", "22:00", item = 9)), from = null, library = true)
        assertEquals(listOf(block("c-1", "20:00", "21:00"), block("c-3", "21:00", "22:00")), checked(unknown).times())
    }

    @Test
    fun anyPagesOfAnyScheduleMergeIntoAnOrderedRowWithoutOverlapsOrHoles() {
        val random = java.util.Random(20261117)
        // A title's files are the same wherever it is shown.
        val catalogue = HashMap<Long, List<Int>>()
        repeat(3000) { round ->
            fun build(firstSeq: Int, start: Int, until: Int, items: Long): List<Entry> {
                val out = ArrayList<Entry>()
                var at = start
                var seq = firstSeq
                var last: Pair<Long, List<Int>>? = null
                while (at < until) {
                    // A third of the time the title before is shown again (a film marathon).
                    val title = last?.takeIf { random.nextInt(3) == 0 } ?: (items + random.nextInt(6)).let { item ->
                        item to catalogue.getOrPut(item) { List(1 + random.nextInt(3)) { 10 + 5 * random.nextInt(12) } }
                    }
                    last = title
                    title.second.forEachIndexed { index, length ->
                        out += Entry(seq++, title.first, title.first * 10 + index, at, at + length)
                        at += length
                    }
                }
                return out
            }
            var entries = build(1, 0, 1500, 100)
            // A page is longer than any title (in the app 6 h; here a title is at most 3 h 15 min).
            val seams = generateSequence(200 + random.nextInt(240)) { it + 200 + random.nextInt(300) }.takeWhile { it < 1240 }.toList()
            val windows = (listOf(0) + seams + 1440).zipWithNext()
            val rebuildAfter = if (round % 2 == 0) -1 else random.nextInt(windows.size)
            var rows = emptyList<ChannelEpgRow>()
            windows.forEachIndexed { index, (from, to) ->
                val page = answer(entries, from, to)
                rows = Epg.merge(rows, page)
                val programs = checked(rows[0].programs)
                // Every programme of the page is in the row from its start (or joined into the one
                // before it) to its end: the newer page is never cut short, nothing is on in its place.
                for (program in page.channels[0].programs) {
                    val start = maxOf(program.startMs!!, Channels.epochMs(page.from)!!)
                    val covering = programs.filter { it.endMs!! > start && it.startMs!! < program.endMs!! }
                    assertTrue("round $round: a hole or a stranger in ${program.id}", covering.isNotEmpty() && covering.all { it.itemId == program.itemId })
                    assertEquals("round $round", program.endMs, covering.last().endMs)
                    assertTrue("round $round", covering.first().startMs!! <= start)
                    covering.zipWithNext { a, b -> assertEquals("round $round: a hole inside ${program.id}", a.endMs, b.startMs) }
                }
                if (rebuildAfter == -1) {
                    // Nothing was rebuilt: the row covers the schedule up to the window's end without
                    // a hole, every block is one title, and no block is longer than its title's files.
                    assertEquals("round $round", Channels.epochMs(at(0)), programs.first().startMs)
                    programs.zipWithNext { a, b -> assertEquals("round $round: a hole", a.endMs, b.startMs) }
                    for (program in programs) {
                        val files = entries.filter { it.item == program.itemId }.distinctBy { it.file }.sumOf { it.end - it.start }
                        assertTrue("round $round: an invented block ${program.id}", program.endMs!! - program.startMs!! <= files * 60_000L)
                    }
                }
                if (index == rebuildAfter) {
                    // The server's rebuild: the entries from some moment on are dropped and made again, numbered on.
                    // The titles after it are partly the same ones, at other times.
                    val moment = from + random.nextInt(to - from + 1)
                    val keep = entries.takeWhile { it.start < moment }
                    val last = keep.lastOrNull()
                    entries = keep + build((last?.seq ?: 0) + 1, last?.end ?: 0, 1500, 103)
                }
            }
        }
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

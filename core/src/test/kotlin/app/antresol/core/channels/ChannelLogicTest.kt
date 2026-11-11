package app.antresol.core.channels

import app.antresol.core.api.ApiException
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.time.Instant

class ChannelLogicTest {
    private fun channel(id: Long, number: Int, category: String) = Channel(id = id, number = number, name = "C$id", category = category)

    private fun program(start: String, end: String, title: String = "P") = ChannelProgram(title = title, start = start, end = end)

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun oneListByNumberWithThemesAndFavouritesAsFilters() {
        val list = listOf(
            channel(1, 4, "Decades"),
            channel(2, 3, "Genres").copy(favorite = true),
            channel(3, 1, "All"),
            channel(4, 2, "Genres"),
            channel(5, 5, "Series").copy(favorite = true),
        )
        assertEquals(listOf("All", "Genres", "Decades", "Series"), Channels.themes(list))
        assertEquals(listOf(3L, 4L, 2L, 1L, 5L), Channels.filter(list, ChannelFilter.All).map { it.id })
        assertEquals(listOf(2L, 5L), Channels.filter(list, ChannelFilter.Favorites).map { it.id })
        assertEquals(listOf(4L, 2L), Channels.filter(list, ChannelFilter.Theme("Genres")).map { it.id })
        assertEquals(ChannelFilter.All, Channels.validFilter(list, ChannelFilter.Theme("Gone")))
        assertEquals(ChannelFilter.Favorites, Channels.validFilter(list, ChannelFilter.Favorites))
        for (filter in listOf(ChannelFilter.All, ChannelFilter.Favorites, ChannelFilter.Theme("Кино: 90-е"))) {
            assertEquals(filter, ChannelFilter.fromKey(filter.key))
        }
        assertEquals(ChannelFilter.All, ChannelFilter.fromKey(null))
    }

    @Test
    fun neighbourWrapsByNumber() {
        val list = listOf(channel(10, 50, "a"), channel(11, 1, "b"), channel(12, 10, "a"))
        assertEquals(12L, Channels.neighbour(list, 11, +1)?.id)
        assertEquals(10L, Channels.neighbour(list, 12, +1)?.id)
        assertEquals(11L, Channels.neighbour(list, 10, +1)?.id)
        assertEquals(10L, Channels.neighbour(list, 11, -1)?.id)
        assertEquals(11L, Channels.neighbour(list, 12, -1)?.id)
        // A channel that disappeared switches to the first one, or by its number when known.
        assertEquals(11L, Channels.neighbour(list, 99, +1)?.id)
        assertEquals(10L, Channels.neighbour(list, 99, +1, currentNumber = 20)?.id)
        assertEquals(12L, Channels.neighbour(list, 99, -1, currentNumber = 20)?.id)
        assertEquals(10L, Channels.neighbour(list, 99, -1, currentNumber = 1)?.id)
        assertNull(Channels.neighbour(list.take(1), 10, +1))
        assertNull(Channels.neighbour(emptyList(), 10, -1))
    }

    @Test
    fun progressOfAProgramme() {
        val p = program("2026-11-10T22:44:00Z", "2026-11-10T23:14:00Z")
        assertEquals(0f, Channels.progress(p, ms("2026-11-10T22:00:00Z")))
        assertEquals(0.5f, Channels.progress(p, ms("2026-11-10T22:59:00Z")), 0.0001f)
        assertEquals(1f, Channels.progress(p, ms("2026-11-11T00:00:00Z")))
        // Server times carry 0–9 fraction digits.
        val fractional = program("2026-11-10T23:12:07.47Z", "2026-11-10T23:13:37.493Z")
        assertEquals(0.5f, Channels.progress(fractional, ms("2026-11-10T23:12:52.4815Z")), 0.001f)
        assertEquals(0f, Channels.progress(program("", ""), 0))
        assertEquals(0f, Channels.progress(program("2026-11-10T23:00:00Z", "2026-11-10T23:00:00Z"), ms("2026-11-10T23:00:00Z")))
    }

    @Test
    fun onAirIsTheFirstProgrammeThatHasNotEnded() {
        val a = program("2026-11-10T10:00:00Z", "2026-11-10T10:30:00Z", "a")
        val b = program("2026-11-10T10:30:00Z", "2026-11-10T11:00:00Z", "b")
        val c = program("2026-11-10T11:00:00Z", "2026-11-10T11:30:00Z", "c")
        val guide = listOf(a, b, c)
        assertEquals(a to b, Channels.onAir(guide, ms("2026-11-10T10:10:00Z")))
        assertEquals(b to c, Channels.onAir(guide, ms("2026-11-10T10:30:00Z")))
        assertEquals(c to null, Channels.onAir(guide, ms("2026-11-10T11:10:00Z")))
        assertEquals(null to null, Channels.onAir(guide, ms("2026-11-10T12:00:00Z")))
        assertEquals(null to null, Channels.onAir(emptyList(), 0))
    }

    @Test
    fun aCardMovesOnToTheNextProgrammeWhenTheListIsStale() {
        val a = program("2026-11-10T10:00:00Z", "2026-11-10T10:30:00Z", "a")
        val b = program("2026-11-10T10:30:00Z", "2026-11-10T11:00:00Z", "b")
        val channel = Channel(id = 1, now = a, next = b)
        assertEquals(a, Channels.showing(channel, ms("2026-11-10T10:29:59Z")))
        assertEquals(b, Channels.showing(channel, ms("2026-11-10T10:30:00Z")))
        assertEquals(a, Channels.showing(channel.copy(next = null), ms("2026-11-10T10:45:00Z")))
        assertNull(Channels.showing(Channel(id = 2), 0))
    }

    @Test
    fun programLineJoinsTheSubtitle() {
        assertEquals("Show · S1 E5 · Episode 5", Channels.programLine(ChannelProgram(title = "Show", subtitle = "S1 E5 · Episode 5")))
        assertEquals("Movie", Channels.programLine(ChannelProgram(title = "Movie")))
    }

    @Test
    fun liveStartRetriesOnlyWhileTheChannelStarts() {
        val starting = ApiException(503, "Starting", retryAfterSec = 2)
        assertEquals(LiveRetry.After(2_000), LiveStartPolicy.decide(starting, 1))
        assertEquals(LiveRetry.Fail("Starting"), LiveStartPolicy.decide(starting, LiveStartPolicy.MAX_ATTEMPTS))
        assertEquals(LiveRetry.Fail("Too many"), LiveStartPolicy.decide(ApiException(503, "Too many", retryAfterSec = 30), 1))
        assertEquals(LiveRetry.Fail("Nothing to show"), LiveStartPolicy.decide(ApiException(503, "Nothing to show"), 1))
        assertEquals(LiveRetry.Fail("Screen time"), LiveStartPolicy.decide(ApiException(403, "Screen time"), 1))
        assertEquals(LiveRetry.Fail("Gone"), LiveStartPolicy.decide(ApiException(404, "Gone"), 1))
        assertEquals(LiveRetry.After(2_000), LiveStartPolicy.decide(IOException("reset"), 3))
        assertEquals(LiveRetry.Fail(null), LiveStartPolicy.decide(IOException("reset"), LiveStartPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun lastChannelWhileItIsVisible() {
        val channels = listOf(channel(1, 1, "a"), channel(2, 2, "b"))
        assertEquals(2L, Channels.lastChannel(channels, 2)?.id)
        assertNull(Channels.lastChannel(channels, null))
        // Gone, or no longer visible to the profile (a kid's age limit).
        assertNull(Channels.lastChannel(channels, 9))
    }

    @Test
    fun miniGuideIsTheOpenedListByNumber() {
        val films = listOf(channel(1, 30, "Films"), channel(3, 10, "Films"), channel(4, 20, "Films"))
        val guide = Channels.miniGuide(films, 4)
        assertEquals(listOf(3L, 4L, 1L), guide.channels.map { it.id })
        assertEquals(1, guide.currentIndex)
        assertEquals(0, Channels.miniGuide(films, 99).currentIndex)
        assertEquals(MiniGuide.Empty, Channels.miniGuide(emptyList(), 1))
    }
}

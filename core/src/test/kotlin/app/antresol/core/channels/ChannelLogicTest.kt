package app.antresol.core.channels

import app.antresol.core.api.ApiException
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelCategory
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

    private fun group(name: String) = ChannelFilter.Category(Channels.groupId(name))

    @Test
    fun oneListByNumberWithCategoriesAndFavouritesAsFilters() {
        val list = listOf(
            channel(1, 4, "Decades"),
            channel(2, 3, "Genres").copy(favorite = true),
            channel(3, 1, "All"),
            channel(4, 2, "Genres"),
            channel(5, 5, "Series").copy(favorite = true),
        )
        assertEquals(listOf("All", "Genres", "Decades", "Series"), Channels.categoryList(list, null).map { it.name })
        // A filter keeps the list's order (the server's: here by number).
        val ordered = Channels.ordered(list, favoritesFirst = false)
        assertEquals(listOf(3L, 4L, 2L, 1L, 5L), Channels.filter(ordered, ChannelFilter.All).map { it.id })
        assertEquals(listOf(2L, 5L), Channels.filter(ordered, ChannelFilter.Favorites).map { it.id })
        assertEquals(listOf(4L, 2L), Channels.filter(ordered, group("Genres")).map { it.id })
        assertEquals(ChannelFilter.All, Channels.validFilter(list, group("Gone")))
        assertEquals(ChannelFilter.Favorites, Channels.validFilter(list, ChannelFilter.Favorites))
        for (filter in listOf(ChannelFilter.All, ChannelFilter.Favorites, group("Кино: 90-е"), ChannelFilter.Category("ai:news"))) {
            assertEquals(filter, ChannelFilter.fromKey(filter.key))
        }
        assertEquals(ChannelFilter.All, ChannelFilter.fromKey(null))
        // A saved key from before categories had ids.
        assertEquals(group("Genres"), ChannelFilter.fromKey("theme:Genres"))
    }

    @Test
    fun theCategoryListOfAnOlderServerHasCountsInPlaylistOrder() {
        // Groups of a playlist: spaces around a name are not another category; some channels have no group.
        val list = listOf(
            channel(1, 1, "News"),
            channel(2, 2, "News "),
            channel(3, 3, ""),
            channel(4, 4, "Sports").copy(favorite = true),
            channel(5, 5, "News").copy(favorite = true),
            channel(6, 6, " "),
            channel(7, 7, "Kids"),
        )
        assertEquals(
            listOf(
                ChannelFilterOption(ChannelFilter.Favorites, 2),
                ChannelFilterOption(ChannelFilter.All, 7),
                ChannelFilterOption(group("News"), 3, "News", ChannelCategory.KIND_GROUP),
                ChannelFilterOption(group("Sports"), 1, "Sports", ChannelCategory.KIND_GROUP),
                ChannelFilterOption(group("Kids"), 1, "Kids", ChannelCategory.KIND_GROUP),
                ChannelFilterOption(ChannelFilter.Category(Channels.NO_CATEGORY), 2, "", ChannelCategory.KIND_GROUP),
            ),
            Channels.filterOptions(list),
        )
        assertEquals(listOf(1L, 2L, 5L), Channels.filter(list, group("News")).map { it.id })
        assertEquals(listOf(3L, 6L), Channels.filter(list, ChannelFilter.Category(Channels.NO_CATEGORY)).map { it.id })
        val none = ChannelFilter.Category(Channels.NO_CATEGORY)
        assertEquals(none, Channels.validFilter(list, none))
        assertEquals(none, ChannelFilter.fromKey(none.key))
    }

    @Test
    fun aListNobodyGroupedHasNoCategoryEntries() {
        val ungrouped = listOf(channel(1, 1, ""), channel(2, 2, ""))
        assertEquals(
            listOf(ChannelFilterOption(ChannelFilter.Favorites, 0), ChannelFilterOption(ChannelFilter.All, 2)),
            Channels.filterOptions(ungrouped),
        )
        assertEquals(ChannelFilter.All, Channels.validFilter(ungrouped, group("Gone")))
        assertEquals(listOf(ChannelFilterOption(ChannelFilter.Favorites, 0), ChannelFilterOption(ChannelFilter.All, 0)), Channels.filterOptions(emptyList()))
    }

    @Test
    fun theServersCategoriesLeadInItsOrderWithTheCountsOfTheChannelsShown() {
        // The server's list: AI topics, then sources, then groups; each channel carries its ids.
        val server = listOf(
            ChannelCategory("ai:news", "Новости", ChannelCategory.KIND_AI, 40),
            ChannelCategory("ai:sport", "Спорт", ChannelCategory.KIND_AI, 9),
            ChannelCategory("source:3", "Playlist A", ChannelCategory.KIND_SOURCE, 5),
            ChannelCategory("group:RU | Новости", "RU | Новости", ChannelCategory.KIND_GROUP, 2),
            ChannelCategory("group:Gone", "Gone", ChannelCategory.KIND_GROUP, 7),
        )
        val list = listOf(
            channel(1, 1, "RU | Новости").copy(categories = listOf("ai:news", "source:3", "group:RU | Новости")),
            channel(2, 2, "RU | Новости").copy(categories = listOf("ai:news", "ai:sport", "group:RU | Новости")),
            channel(3, 3, "Own", ).copy(categories = listOf("group:Own"), favorite = true),
            // The server sends nothing for a channel that is in no category.
            channel(4, 4, ""),
        )
        val options = Channels.filterOptions(list, server)
        assertEquals(
            listOf(
                ChannelFilterOption(ChannelFilter.Favorites, 1),
                ChannelFilterOption(ChannelFilter.All, 4),
                ChannelFilterOption(ChannelFilter.Category("ai:news"), 2, "Новости", "ai"),
                ChannelFilterOption(ChannelFilter.Category("ai:sport"), 1, "Спорт", "ai"),
                ChannelFilterOption(ChannelFilter.Category("source:3"), 1, "Playlist A", "source"),
                ChannelFilterOption(ChannelFilter.Category("group:RU | Новости"), 2, "RU | Новости", "group"),
            ),
            options,
        )
        assertEquals(listOf(1L, 2L), Channels.filter(list, ChannelFilter.Category("ai:news")).map { it.id })
        assertEquals(listOf(2L), Channels.filter(list, ChannelFilter.Category("ai:sport")).map { it.id })
        assertEquals(listOf(1L, 2L), Channels.filter(list, ChannelFilter.Category("group:RU | Новости")).map { it.id })
        assertEquals(ChannelFilter.All, Channels.validFilter(list, ChannelFilter.Category("ai:kids")))
        // A channel's categories: the server's, else its own text as a group.
        assertEquals(listOf("group:Own"), Channels.categoriesOf(list[2]))
        assertEquals(emptyList<String>(), Channels.categoriesOf(list[3]))
        assertEquals(listOf("group:Genres"), Channels.categoriesOf(channel(9, 9, " Genres ")))
    }

    @Test
    fun withoutTheServersListOnlyGroupsAreNamed() {
        val list = listOf(
            channel(1, 1, "News").copy(categories = listOf("ai:news", "group:News")),
            channel(2, 2, "News").copy(categories = listOf("ai:news", "source:3", "group:News")),
        )
        assertEquals(listOf(group("News")), Channels.filterOptions(list).drop(2).map { it.filter })
    }

    @Test
    fun aCategoryIsNamedByTheServersListThenByItsGroup() {
        val names = listOf(ChannelCategory("ai:news", "Новости", ChannelCategory.KIND_AI, 3))
        assertEquals("Новости", Channels.categoryName(ChannelFilter.Category("ai:news"), names))
        assertEquals("RU | Спорт", Channels.categoryName(ChannelFilter.Category("group:RU | Спорт"), names))
        assertNull(Channels.categoryName(ChannelFilter.Category("ai:kids"), names))
        assertNull(Channels.categoryName(ChannelFilter.Category(Channels.NO_CATEGORY), names))
    }

    @Test
    fun neighbourWrapsInTheListsOrder() {
        val list = Channels.ordered(listOf(channel(10, 50, "a"), channel(11, 1, "b"), channel(12, 10, "a")), favoritesFirst = false)
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
        assertNull(Channels.neighbour(list.filter { it.id == 10L }, 10, +1))
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
    fun miniGuideIsTheOpenedListInItsOrder() {
        val films = listOf(channel(3, 10, "Films"), channel(4, 20, "Films"), channel(1, 30, "Films"))
        val guide = Channels.miniGuide(films, 4)
        assertEquals(listOf(3L, 4L, 1L), guide.channels.map { it.id })
        assertEquals(1, guide.currentIndex)
        assertEquals(0, Channels.miniGuide(films, 99).currentIndex)
        assertEquals(MiniGuide.Empty, Channels.miniGuide(emptyList(), 1))
    }

    @Test
    fun serverTimesAreReadWithAnyZoneOffset() {
        val utc = ms("2026-11-18T10:42:00Z")
        assertEquals(utc, Channels.epochMs("2026-11-18T10:42:00Z"))
        // Go's time.Time JSON in a server zone other than UTC
        assertEquals(utc, Channels.epochMs("2026-11-18T13:42:00+03:00"))
        assertEquals(utc, Channels.epochMs("2026-11-18T05:42:00-05:00"))
        assertEquals(utc, Channels.epochMs("2026-11-18T10:42:00+00:00"))
        // fractional seconds, as time.UnixMilli gives them
        assertEquals(utc + 123, Channels.epochMs("2026-11-18T13:42:00.123+03:00"))
        assertEquals(utc + 123, Channels.epochMs("2026-11-18T10:42:00.123456789Z"))
        assertEquals(ms("2026-11-18T10:43:00Z"), Channels.displayMs("2026-11-18T13:42:40.5+03:00"))
        assertNull(Channels.epochMs("2026-11-18 13:42"))
        assertNull(Channels.epochMs("2026-11-18T13:42:00"))
        assertNull(Channels.epochMs(""))
    }

    @Test
    fun displayedTimesRoundToTheNearestMinute() {
        assertEquals(ms("2026-11-11T23:51:00Z"), Channels.displayMs("2026-11-11T23:50:38.9Z"))
        assertEquals(ms("2026-11-11T23:50:00Z"), Channels.displayMs("2026-11-11T23:50:29.999Z"))
        assertEquals(ms("2026-11-11T23:51:00Z"), Channels.displayMs("2026-11-11T23:50:30Z"))
        assertEquals(ms("2026-11-12T00:00:00Z"), Channels.displayMs("2026-11-11T23:59:45Z"))
        assertEquals(ms("2026-11-11T23:50:00Z"), Channels.displayMs("2026-11-11T23:50:00Z"))
        assertNull(Channels.displayMs(""))
        // 23:50:38 → 23:56:10 is shown 23:51–23:56: five minutes.
        assertEquals(5, Channels.displayMinutes(program("2026-11-11T23:50:38Z", "2026-11-11T23:56:10Z")))
    }

    @Test
    fun aRowShowsNumberNameProgrammeAndProgressOnly() {
        val now = program("2026-11-10T10:00:00Z", "2026-11-10T11:00:00Z", "Film").copy(backdrop = "/b", poster = "/p")
        val row = Channels.rowContent(channel(1, 7, "x").copy(favorite = true, now = now), ms("2026-11-10T10:30:00Z"))
        assertEquals(ChannelRowContent(7, "C1", true, "Film", 0.5f, "/b"), row)
        val empty = Channels.rowContent(channel(2, 8, "x"), 0)
        assertEquals(ChannelRowContent(8, "C2", false, null, null, null), empty)
        assertEquals("/p", Channels.rowContent(channel(1, 7, "x").copy(now = now.copy(backdrop = "")), 0).art)
    }

    @Test
    fun theServersOrderHasTheFavouritesFirstWhenTheProfileWantsIt() {
        val list = listOf(
            channel(1, 1, "a"),
            channel(2, 2, "a").copy(favorite = true),
            channel(3, 3, "b"),
            channel(4, 4, "b").copy(favorite = true),
        )
        assertEquals(listOf(2L, 4L, 1L, 3L), Channels.ordered(list.shuffled(java.util.Random(7)), favoritesFirst = true).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), Channels.ordered(list.reversed(), favoritesFirst = false).map { it.id })
        // Filters, zapping and the mini-guide follow the list as given (favourites first), not the numbers.
        val ordered = Channels.ordered(list, favoritesFirst = true)
        assertEquals(listOf(4L, 3L), Channels.filter(ordered, group("b")).map { it.id })
        assertEquals(1L, Channels.neighbour(ordered, 4, +1)?.id)
        assertEquals(3L, Channels.neighbour(ordered, 2, -1)?.id)
        assertEquals(listOf(2L, 4L, 1L, 3L), Channels.miniGuide(ordered, 1).channels.map { it.id })
        assertEquals(2, Channels.miniGuide(ordered, 1).currentIndex)
    }

    @Test
    fun adultCategoriesGoLast() {
        fun cat(id: String, name: String) = ChannelCategory(id, name, ChannelCategory.KIND_GROUP, 1)
        val server = listOf(cat("g1", "Новости"), cat("g2", "Для взрослых"), cat("g3", "Кино"), cat("g4", "XXX"), cat("g5", "18+"), cat("g6", "Детские"))
        val channels = server.mapIndexed { i, c -> Channel(id = i + 1L, number = i + 1, name = "c$i", categories = listOf(c.id)) }
        assertEquals(listOf("Новости", "Кино", "Детские", "Для взрослых", "XXX", "18+"), Channels.categoryList(channels, server).map { it.name })
        assertEquals(false, Channels.isAdult(cat("g7", "Взрослые мультфильмы о жизни").copy(name = "Мультфильмы")))
        assertEquals(true, Channels.isAdult(cat("group:Взрослое", "")))
        // A filter's favourites and «All» stay first.
        assertEquals(ChannelFilter.Favorites, Channels.filterOptions(channels, server).first().filter)
    }

    @Test
    fun anExclusiveCategoryKeepsItsChannelsToItselfAndComesLast() {
        val adult = ChannelCategory("ai:adult", "18+", ChannelCategory.KIND_AI, exclusive = true)
        val films = ChannelCategory("ai:films", "Фильмы", ChannelCategory.KIND_AI)
        val server = listOf(adult, films)
        val a = Channel(id = 1, number = 1, name = "a", favorite = true, categories = listOf("ai:films"))
        val b = Channel(id = 2, number = 2, name = "b", favorite = true, categories = listOf("ai:adult", "ai:films"))
        val all = listOf(a, b)
        assertEquals(listOf(a), Channels.filter(all, ChannelFilter.All, server))
        assertEquals(listOf(a), Channels.filter(all, ChannelFilter.Favorites, server))
        assertEquals(listOf(a), Channels.filter(all, ChannelFilter.Category("ai:films"), server))
        assertEquals(listOf(b), Channels.filter(all, ChannelFilter.Category("ai:adult"), server))
        assertEquals(listOf("Фильмы" to 1, "18+" to 1), Channels.categoryList(all, server).map { it.name to it.count })
        val options = Channels.filterOptions(all, server)
        assertEquals(listOf(1, 1), options.take(2).map { it.count })
        // Without the server's list (or the flag) nothing is hidden.
        assertEquals(all, Channels.filter(all, ChannelFilter.All))
    }
}

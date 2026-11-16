package app.antresol.core.library

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.EpisodeGuideResponse
import app.antresol.core.model.GuideEntry
import app.antresol.core.model.GuideSource
import app.antresol.core.model.GuideSourceMeta
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.SeasonInfo
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EpisodeGuideTest {
    private val today = LocalDate.of(2026, 11, 14)

    private fun card(id: Long, season: Int, no: Int, watched: Boolean = false, progress: Double = 0.0) =
        EpisodeCard(id = id, title = "Library $no", seasonNo = season, episodeNo = no, watched = watched, progress = progress)

    private val series = ItemDetail(
        id = 1,
        kind = Kind.SERIES,
        tmdbId = 1399,
        seasons = listOf(
            SeasonInfo(id = 10, number = 1, title = "Season 1", episodes = listOf(card(11, 1, 1, watched = true), card(12, 1, 2, progress = 0.4))),
            SeasonInfo(id = 20, number = 2, title = "Season 2", episodes = listOf(card(21, 2, 1))),
            SeasonInfo(id = 30, number = 3, title = "Empty", episodes = emptyList()),
        ),
    )

    private fun library(itemId: Long) = GuideSource(kind = GuideSource.KIND_LIBRARY, ref = "item:$itemId", meta = GuideSourceMeta(itemId = itemId))

    private fun entry(season: Int, no: Int, airDate: String, vararg sources: GuideSource, watched: Boolean = false) =
        GuideEntry(season = season, number = no, name = "TMDB $season×$no", airDate = airDate, watched = watched, sources = sources.toList())

    @Test
    fun noGuideKeepsTheLibraryList() {
        for (guide in listOf(null, EpisodeGuideResponse())) {
            val result = EpisodeGuide.build(series, guide, today)
            assertFalse(result.fromServer)
            assertEquals(listOf(1, 2), result.seasons.map { it.number })
            assertEquals(listOf(10L, 20L), result.seasons.map { it.libraryId })
            assertEquals(listOf(11L, 12L), result.seasons[0].libraryEpisodes.map { it.id })
            assertTrue(result.seasons.flatMap { it.episodes }.all { it.state == EpisodeState.Downloaded && it.isPlayable })
            assertTrue(result.seasons[0].episodes[0].watched)
            assertEquals(0.4, result.seasons[0].episodes[1].progress, 0.0)
        }
    }

    @Test
    fun downloadedMissingAndUpcoming() {
        val guide = EpisodeGuideResponse(
            episodes = listOf(
                entry(1, 1, "2026-01-05", library(11)),
                entry(1, 2, "2026-01-12", library(12)),
                entry(1, 3, "2026-01-19", GuideSource(kind = "x-source", ref = "x:1"), watched = true),
                entry(1, 4, "2026-11-14"), // airs today: aired
                entry(1, 5, "2026-11-15"),
                entry(1, 6, ""),
                entry(2, 1, "2026-02-01", library(21)),
            ),
        )
        val result = EpisodeGuide.build(series, guide, today)
        assertTrue(result.fromServer)
        assertEquals(listOf(1, 2), result.seasons.map { it.number })
        val s1 = result.seasons[0].episodes
        assertEquals((1..6).toList(), s1.map { it.number })
        assertEquals(
            listOf(
                EpisodeState.Downloaded, EpisodeState.Downloaded, EpisodeState.NotDownloaded,
                EpisodeState.NotDownloaded, EpisodeState.Upcoming, EpisodeState.Upcoming,
            ),
            s1.map { it.state },
        )
        // library episodes keep the library card (title, watched, progress) and play by its id
        assertEquals(11L, s1[0].card?.id)
        assertEquals("Library 1", s1[0].title)
        assertTrue(s1[0].watched)
        assertEquals(0.4, s1[1].progress, 0.0)
        assertEquals(LocalDate.of(2026, 1, 12), s1[1].airDate)
        // the others are TMDB's, with the guide's watched mark, never playable
        assertNull(s1[2].card)
        assertFalse(s1[2].isPlayable)
        assertTrue(s1[2].watched)
        assertEquals("TMDB 1×3", s1[2].title)
        assertEquals(LocalDate.of(2026, 11, 15), s1[4].airDate)
        assertNull(s1[5].airDate)
        assertEquals(listOf(11L, 12L), result.seasons[0].libraryEpisodes.map { it.id })
        assertEquals(10L, result.seasons[0].libraryId)
        assertEquals("Season 1", result.seasons[0].title)
        assertEquals(s1.size, s1.map { it.key }.toSet().size)
    }

    @Test
    fun seasonsOnlyTmdbKnowsShowWithoutALibrarySeason() {
        val guide = EpisodeGuideResponse(
            episodes = listOf(
                entry(1, 1, "2026-01-05", library(11)),
                entry(4, 1, "2027-03-01"),
            ),
        )
        val result = EpisodeGuide.build(series, guide, today)
        assertEquals(listOf(1, 2, 4), result.seasons.map { it.number })
        val s4 = result.seasons.last()
        assertNull(s4.libraryId)
        assertEquals("", s4.title)
        assertEquals(EpisodeState.Upcoming, s4.episodes.single().state)
        // library episodes the guide does not mention are kept (s1e2 and season 2)
        assertEquals(listOf(11L, 12L), result.seasons[0].libraryEpisodes.map { it.id })
        assertEquals(listOf(21L), result.seasons[1].libraryEpisodes.map { it.id })
    }

    @Test
    fun libraryEpisodeWithoutASourceMatchesBySeasonAndNumber() {
        val guide = EpisodeGuideResponse(episodes = listOf(entry(1, 1, "2026-01-05"), entry(1, 2, "2026-01-12")))
        val s1 = EpisodeGuide.build(series, guide, today).seasons[0].episodes
        assertEquals(listOf(11L, 12L), s1.map { it.card?.id })
        assertTrue(s1.all { it.state == EpisodeState.Downloaded })
    }

    @Test
    fun librarySourceWinsOverNumbering() {
        // absolute numbering: the library's 1×2 is TMDB's 2×1; 1×2 on TMDB is not downloaded
        val guide = EpisodeGuideResponse(
            episodes = listOf(entry(1, 2, "2026-01-12"), entry(2, 1, "2026-02-01", library(12))),
        )
        val result = EpisodeGuide.build(series, guide, today)
        val s1 = result.seasons.first { it.number == 1 }.episodes
        assertEquals(EpisodeState.NotDownloaded, s1.single { it.number == 2 }.state)
        val s2 = result.seasons.first { it.number == 2 }.episodes
        assertEquals(listOf(12L, 21L), s2.mapNotNull { it.card?.id })
    }

    @Test
    fun librarySourceUnknownToTheDetailStillPlays() {
        val guide = EpisodeGuideResponse(episodes = listOf(entry(0, 1, "", library(99))))
        val special = EpisodeGuide.build(series, guide, today).seasons.first { it.number == 0 }.episodes.single()
        assertEquals(EpisodeState.Downloaded, special.state)
        assertEquals(99L, special.card?.id)
        assertEquals("TMDB 0×1", special.title)
    }

    @Test
    fun parsesDates() {
        assertEquals(LocalDate.of(2026, 1, 5), EpisodeGuide.parseDate("2026-01-05"))
        assertNull(EpisodeGuide.parseDate(""))
        assertNull(EpisodeGuide.parseDate("soon"))
        assertNull(EpisodeGuide.parseDate("2026-13-45"))
    }

    @Test
    fun fetchFallsBackOnEveryFailure() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val client = AntresolClient(server.url("/").toString())
            server.enqueue(MockResponse().setBody("""{"title":{"kind":"series","tmdbId":1399},"episodes":[{"season":1,"number":1,"airDate":"2026-01-05","sources":[]}]}"""))
            assertNotNull(EpisodeGuide.fetch(client, series))
            assertEquals("/api/tracking/series/1399/episodes", server.takeRequest().path)
            for (code in listOf(404, 405, 500)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"Не найдено"}"""))
                assertNull(EpisodeGuide.fetch(client, series))
                server.takeRequest()
            }
            server.enqueue(MockResponse().setBody("not json"))
            assertNull(EpisodeGuide.fetch(client, series))
            server.takeRequest()
            // nothing is asked for a series without a TMDB id, or for a season
            assertNull(EpisodeGuide.fetch(client, series.copy(tmdbId = null)))
            assertNull(EpisodeGuide.fetch(client, series.copy(kind = Kind.SEASON)))
            assertEquals(5, server.requestCount)
            // a slow answer is not waited for
            server.enqueue(MockResponse().setBody("{}").setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS))
            assertNull(EpisodeGuide.fetch(client, series, timeoutMs = 200))
        } finally {
            server.shutdown()
        }
    }
}

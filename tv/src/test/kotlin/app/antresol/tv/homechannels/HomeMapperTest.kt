package app.antresol.tv.homechannels

import app.antresol.core.model.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Server item → program: what Watch Next and the «Antresol» channel show for what the server sends. */
class HomeMapperTest {
    private fun watchNext(vararg items: ContinueItem) = HomeMapper.watchNext(snapshot(continueItems = items.toList()))

    @Test
    fun aStartedMovieContinuesWithItsPositionAndLength() {
        val movie = card(19, year = 2019, backdrop = "/api/images/local/19/backdrop", progress = 0.1)
        val program = watchNext(started(movie, positionMs = 600_000, durationMs = 6_000_000)).single()

        assertEquals(Slot.WatchNext, program.slot)
        assertEquals(WatchNextKind.Continue, program.watchNext)
        assertEquals(ProgramKind.Movie, program.kind)
        assertEquals("Title 19", program.title)
        assertEquals("2019", program.description)
        assertEquals(600_000, program.positionMs)
        assertEquals(6_000_000, program.durationMs)
        assertEquals("wn:srv:7:movie:19", program.key)
        assertEquals(HomeLink.Play(19, USER), program.link)
        assertEquals(0, program.rank)
    }

    @Test
    fun anEpisodeIsTitledByItsSeriesWithTheEpisodeBelow() {
        val episode = card(
            601, Kind.EPISODE, title = "Episode 2", subtitle = "The Test Show · Season 1, Episode 2", seriesId = 500,
            thumb = "/api/images/frame/601", backdrop = "/api/images/local/500/backdrop", progress = 0.4, season = 1, episode = 2,
        )
        val program = watchNext(started(episode)).single()

        assertEquals(ProgramKind.Episode, program.kind)
        assertEquals("The Test Show", program.title)
        assertEquals("Season 1, Episode 2", program.description)
        assertEquals("Episode 2", program.episodeTitle)
        assertEquals(1, program.season)
        assertEquals(2, program.episode)
    }

    @Test
    fun anEpisodeWithOtherSubtitleShapeKeepsItsOwnTitle() {
        val episode = card(601, Kind.EPISODE, title = "Pilot", subtitle = "S1E1", progress = 0.5)
        val program = watchNext(started(episode)).single()
        assertEquals("Pilot", program.title)
        assertEquals("S1E1", program.description)
        assertNull(program.episodeTitle)
    }

    @Test
    fun aNextUpEpisodeHasNoPositionAndIsNotAContinue() {
        val next = card(602, Kind.EPISODE, subtitle = "Show · Season 1, Episode 3", progress = 0.0)
        val program = watchNext(ContinueItem(next, null)).single()

        assertEquals(WatchNextKind.Next, program.watchNext)
        assertEquals(0, program.positionMs)
        assertEquals(0, program.durationMs)
    }

    @Test
    fun aStartedItemWhoseProgressIsMissingIsListedAsNext() {
        val program = watchNext(ContinueItem(card(5, progress = 0.3), null)).single()
        assertEquals(WatchNextKind.Next, program.watchNext)
        assertEquals(0, program.durationMs)
    }

    @Test
    fun aPositionPastTheEndIsHeldAtTheEnd() {
        val program = watchNext(started(card(5, progress = 0.99), positionMs = 7_000_000, durationMs = 6_000_000)).single()
        assertEquals(6_000_000, program.positionMs)
    }

    @Test
    fun finishedWatchedAndNonVideoCardsAreLeftOut() {
        val items = arrayOf(
            started(card(1, progress = 0.5), finished = true),
            started(card(2, progress = 0.5).copy(watched = true)),
            started(card(3, Kind.ALBUM, progress = 0.5)),
            started(card(4, Kind.AUDIOBOOK, progress = 0.5)),
            started(card(5, progress = 0.5)),
        )
        assertEquals(listOf(5L), (watchNext(*items)).map { (it.link as HomeLink.Play).itemId })
    }

    @Test
    fun theServersOrderIsKeptAndRanksCount() {
        val programs = watchNext(started(card(30, progress = .1)), started(card(10, progress = .1)), started(card(20, progress = .1)))
        assertEquals(listOf(30L, 10L, 20L), programs.map { (it.link as HomeLink.Play).itemId })
        assertEquals(listOf(0, 1, 2), programs.map { it.rank })
    }

    @Test
    fun theRowHoldsAtMostTenAndNoTitleTwice() {
        val items = (1L..14L).map { started(card(it, progress = 0.2)) } + started(card(1, progress = 0.2))
        val programs = watchNext(*items.toTypedArray())
        assertEquals(HomeMapper.MAX_WATCH_NEXT, programs.size)
        assertEquals(programs.size, programs.map { it.key }.toSet().size)
    }

    @Test
    fun anEpisodeShowsItsOwnFrameInAWideTileAndAMovieWithOnlyAPosterShowsThat() {
        val framed = card(601, Kind.EPISODE, thumb = "/frame/601", backdrop = "/bd/500", progress = 0.5)
        val wide = watchNext(started(framed)).single()
        assertEquals(ArtworkRef("/frame/601", "/bd/500"), wide.artwork)
        assertEquals(Aspect.Wide, wide.aspect)

        val backdropOnly = watchNext(started(card(7, backdrop = "/bd/7", progress = 0.5))).single()
        assertEquals(ArtworkRef("/bd/7", ""), backdropOnly.artwork)
        assertEquals(Aspect.Wide, backdropOnly.aspect)

        val posterOnly = watchNext(started(card(8, poster = "/poster/8", progress = 0.5))).single()
        assertEquals(ArtworkRef("/poster/8"), posterOnly.artwork)
        assertEquals(Aspect.Poster, posterOnly.aspect)
    }

    @Test
    fun theKeyBelongsToTheProfileSoAnotherProfilesTilesAreNotTheSame() {
        val a = HomeMapper.watchNext(snapshot(listOf(started(card(1, progress = .2))), userId = 1)).single()
        val b = HomeMapper.watchNext(snapshot(listOf(started(card(1, progress = .2))), userId = 2)).single()
        assertTrue(a.key != b.key)
    }

    // The channel

    private fun channel(movies: List<app.antresol.core.model.Card> = emptyList(), series: List<app.antresol.core.model.Card> = emptyList(), vararg started: ContinueItem) =
        HomeMapper.channel(snapshot(started.toList(), movies, series))

    @Test
    fun theChannelShowsNewMoviesAndSeriesOneAfterTheOther() {
        val movies = listOf(card(1, year = 2020), card(2), card(3))
        val series = listOf(card(10, Kind.SERIES, year = 2021), card(11, Kind.SERIES))
        val programs = channel(movies, series)

        assertEquals(listOf(1L, 10L, 2L, 11L, 3L), programs.map { (it.link as HomeLink.Open).itemId })
        assertEquals(listOf(0, 1, 2, 3, 4), programs.map { it.rank })
        val first = programs.first()
        assertEquals(Slot.Channel, first.slot)
        assertEquals(ProgramKind.Movie, first.kind)
        assertEquals(HomeLink.Open("movie", 1, USER), first.link)
        assertEquals("2020", first.description)
        assertEquals(2020, first.year)
        assertEquals(Aspect.Poster, first.aspect)
        assertEquals(ArtworkRef("/api/images/local/1/poster"), first.artwork)
        assertEquals(ProgramKind.Series, programs[1].kind)
        assertEquals("ch:srv:7:series:10", programs[1].key)
    }

    @Test
    fun theChannelSkipsWatchedTitlesAndTitlesWithoutAPoster() {
        val programs = channel(
            movies = listOf(card(1, watched = true), card(2, poster = ""), card(3)),
            series = listOf(card(10, Kind.SERIES, poster = ""), card(11, Kind.SERIES, watched = true)),
        )
        assertEquals(listOf(3L), programs.map { (it.link as HomeLink.Open).itemId })
    }

    @Test
    fun theChannelDoesNotRepeatWhatIsContinuingInWatchNext() {
        val continuing = started(card(601, Kind.EPISODE, seriesId = 10, progress = 0.4))
        val inProgressMovie = started(card(2, progress = 0.3))
        val programs = channel(
            movies = listOf(card(1), card(2)),
            series = listOf(card(10, Kind.SERIES), card(11, Kind.SERIES)),
            continuing, inProgressMovie,
        )
        assertEquals(listOf(1L, 11L), programs.map { (it.link as HomeLink.Open).itemId })
    }

    @Test
    fun theChannelOffersOnlyMoviesAndSeriesAndAtMostTwenty() {
        val programs = channel(
            movies = listOf(card(500, Kind.ALBUM)) + (1L..30L).map { card(it) },
            series = (101L..130L).map { card(it, Kind.SERIES) },
        )
        assertEquals(HomeMapper.MAX_CHANNEL, programs.size)
        assertTrue(programs.none { it.kind != ProgramKind.Movie && it.kind != ProgramKind.Series })
        assertTrue(programs.none { (it.link as HomeLink.Open).itemId == 500L })
    }

    @Test
    fun anEmptyServerAnswerGivesNothing() {
        assertTrue(HomeMapper.watchNext(snapshot()).isEmpty())
        assertTrue(HomeMapper.channel(snapshot()).isEmpty())
    }

    @Test
    fun theFingerprintChangesWithWhatIsShownAndNotWithTheRun() {
        val base = watchNext(started(card(1, progress = 0.1), positionMs = 1000)).single()
        val same = watchNext(started(card(1, progress = 0.1), positionMs = 1000)).single()
        val moved = watchNext(started(card(1, progress = 0.1), positionMs = 2000)).single()
        assertEquals(base.fingerprint(), same.fingerprint())
        assertTrue(base.fingerprint() != moved.fingerprint())
        assertTrue(base.fingerprint() != base.copy(posterUri = "content://x/y").fingerprint())
        assertTrue(base.fingerprint() != base.copy(rank = 3).fingerprint())
        // Stored in the provider and compared by the next process: the digest of a fixed program must not drift.
        assertEquals(GOLDEN, base.copy(posterUri = "content://app.antresol.mobile.homeart/abc").fingerprint())
    }

    private companion object {
        const val GOLDEN = -6633702925301262597L
    }
}

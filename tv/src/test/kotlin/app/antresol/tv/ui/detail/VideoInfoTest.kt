package app.antresol.tv.ui.detail

import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.FileInfo
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.Progress
import app.antresol.core.model.SeasonInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoInfoTest {
    private val movie = ItemDetail(id = 7, kind = Kind.MOVIE, files = listOf(FileInfo(id = 70)), inWatchlist = true)

    @Test
    fun movieResumesUnlessFinished() {
        val partial = movie.videoInfo(Progress(positionMs = 65_000, durationMs = 600_000, updatedAt = "2026-11-04T00:00:00Z"))
        assertEquals(PlayTarget(itemId = 7, resumeMs = 65_000), partial.playTarget)
        assertTrue(partial.canWatchlist && partial.inWatchlist)
        assertFalse(partial.watched)

        val finished = movie.videoInfo(Progress(positionMs = 0, finished = true, updatedAt = "2026-11-04T00:00:00Z"))
        assertEquals(0L, finished.playTarget?.resumeMs)
        assertTrue(finished.watched)

        assertEquals(0L, movie.videoInfo(null).playTarget?.resumeMs)
    }

    @Test
    fun seriesPlaysTheNextEpisode() {
        val e1 = EpisodeCard(id = 11, seasonNo = 1, episodeNo = 1, watched = true, durationMs = 1_000_000)
        val e2 = EpisodeCard(id = 12, seasonNo = 1, episodeNo = 2, progress = 0.25, durationMs = 1_000_000)
        val series = ItemDetail(
            id = 1,
            kind = Kind.SERIES,
            seasons = listOf(SeasonInfo(id = 2, number = 2), SeasonInfo(id = 1, number = 1, episodes = listOf(e1, e2))),
            next = e2,
        )
        val info = series.videoInfo(null)
        assertEquals(PlayTarget(itemId = 12, resumeMs = 250_000, season = 1, episode = 2), info.playTarget)
        assertFalse(info.watched)
        // Seasons without episodes are hidden.
        assertEquals(listOf(1L), info.seasons.map { it.id })

        val allWatched = series.copy(seasons = listOf(SeasonInfo(id = 1, number = 1, episodes = listOf(e1, e2.copy(watched = true)))), next = null)
        assertTrue(allWatched.videoInfo(null).watched)
        assertEquals(PlayTarget(itemId = 11, resumeMs = 0, season = 1, episode = 1), allWatched.videoInfo(null).playTarget)
    }
}

package app.antresol.ui.format

import app.antresol.core.library.EpisodeState
import org.junit.Assert.assertEquals
import org.junit.Test

/** How strongly an episode row draws: dimmed when nothing plays it, bright when an addon's way does. */
class EpisodeAlphaTest {
    @Test
    fun aLibraryEpisodeIsNeverDimmed() {
        assertEquals(1f, EpisodeLabels.alpha(EpisodeState.Downloaded), 0f)
        assertEquals(1f, EpisodeLabels.alpha(EpisodeState.Downloaded, playable = true), 0f)
    }

    @Test
    fun aMissingEpisodeNothingPlaysIsDimmed() {
        assertEquals(0.6f, EpisodeLabels.alpha(EpisodeState.NotDownloaded), 0f)
        assertEquals(0.6f, EpisodeLabels.alpha(EpisodeState.NotDownloaded, playable = false), 0f)
    }

    @Test
    fun aMissingEpisodeAWayPlaysDrawsAsALibraryOne() {
        assertEquals(1f, EpisodeLabels.alpha(EpisodeState.NotDownloaded, playable = true), 0f)
    }

    @Test
    fun anUpcomingEpisodeStaysGreyedWhateverTheWays() {
        assertEquals(0.38f, EpisodeLabels.alpha(EpisodeState.Upcoming), 0f)
        assertEquals(0.38f, EpisodeLabels.alpha(EpisodeState.Upcoming, playable = true), 0f)
    }
}

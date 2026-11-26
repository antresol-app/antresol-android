package app.antresol.tv.ui.detail

import app.antresol.core.model.Kind
import app.antresol.ui.playback.ResumeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Who chose the episode: the page's Play/Resume button lets the app choose on a series or season page, and is the person's pick on an episode's own page. */
class PrimaryPlayModeTest {
    @Test
    fun anEpisodesOwnPageIsThePersonsPick() {
        assertEquals(ResumeMode.Picked, primaryPlayMode(Kind.EPISODE))
    }

    @Test
    fun aSeriesOrSeasonPageAndAFilmLeaveItToTheApp() {
        assertEquals(ResumeMode.Resume, primaryPlayMode(Kind.SERIES))
        assertEquals(ResumeMode.Resume, primaryPlayMode(Kind.SEASON))
        assertEquals(ResumeMode.Resume, primaryPlayMode(Kind.MOVIE))
    }
}

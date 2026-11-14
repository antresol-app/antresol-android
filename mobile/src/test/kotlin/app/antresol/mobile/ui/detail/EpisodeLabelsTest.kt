package app.antresol.mobile.ui.detail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.antresol.core.library.EpisodeState
import app.antresol.core.library.GuideEpisode
import app.antresol.core.library.GuideSeason
import app.antresol.ui.format.EpisodeLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

/** The guide's marks in both languages (ui-common strings; run here, where the resources are merged). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpisodeLabelsTest {
    private val today = LocalDate.of(2026, 11, 14)

    private fun episode(state: EpisodeState, airDate: LocalDate? = null) =
        GuideEpisode(season = 1, number = 1, title = "", airDate = airDate, state = state, card = null, watched = false, progress = 0.0)

    @Test
    @Config(qualifiers = "en")
    fun englishMarks() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        assertNull(EpisodeLabels.state(res, episode(EpisodeState.Downloaded), guided = false, today))
        assertEquals("Downloaded", EpisodeLabels.state(res, episode(EpisodeState.Downloaded), guided = true, today))
        assertEquals("Not downloaded", EpisodeLabels.state(res, episode(EpisodeState.NotDownloaded), guided = true, today))
        assertEquals("Not aired yet", EpisodeLabels.state(res, episode(EpisodeState.Upcoming), guided = true, today))
        assertEquals("Specials", EpisodeLabels.season(res, GuideSeason(0, "", null, emptyList())))
        assertEquals("Season 3", EpisodeLabels.season(res, GuideSeason(3, "", null, emptyList())))
        assertEquals("Book One", EpisodeLabels.season(res, GuideSeason(1, "Book One", 5, emptyList())))
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianAirDate() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        val ru = Locale("ru")
        assertEquals("21 ноября", EpisodeLabels.airDate(LocalDate.of(2026, 11, 21), today, ru))
        assertEquals(true, EpisodeLabels.airDate(LocalDate.of(2027, 1, 2), today, ru).contains("2027"))
        val label = EpisodeLabels.state(res, episode(EpisodeState.Upcoming, LocalDate.of(2026, 11, 21)), guided = true, today)
        assertEquals(true, label!!.startsWith("Выйдет "))
        assertEquals("Не скачана", EpisodeLabels.state(res, episode(EpisodeState.NotDownloaded), guided = true, today))
    }
}

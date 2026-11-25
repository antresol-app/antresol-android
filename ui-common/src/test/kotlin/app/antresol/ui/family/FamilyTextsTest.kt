package app.antresol.ui.family

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import app.antresol.core.family.FamilyKind
import app.antresol.core.model.Chapter
import app.antresol.core.model.FamilyMember
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The words of «Семья» in English and Russian: where a profile is and what continuing says (the web's strings). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyTextsTest {
    private fun resources(language: String): Resources {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale(language)) }
        return context.createConfigurationContext(config).resources
    }

    private val en get() = resources("en")
    private val ru get() = resources("ru")

    private fun member(
        positionMs: Long = 0,
        durationMs: Long = 0,
        fraction: Double = 0.0,
        finished: Boolean = false,
        next: Boolean = false,
        season: Int = 0,
        episode: Int = 0,
        volume: Double = 0.0,
        title: String = "",
        locator: String = "",
        page: Int = 0,
        pages: Int = 0,
    ) = FamilyMember(
        userId = 2, name = "Sam", itemId = 10, positionMs = positionMs, durationMs = durationMs, fraction = fraction, finished = finished,
        next = next, season = season, episode = episode, volume = volume, title = title, locator = locator, page = page, pages = pages,
    )

    @Test
    fun aSeriesSaysTheEpisodeAndHowFarIn() {
        val m = member(positionMs = 17 * 60_000, season = 2, episode = 5)
        assertEquals("S2 E5 · 17 min", FamilyTexts.placeOf(en, FamilyKind.Series, m))
        assertEquals("С2 Э5 · 17 мин", FamilyTexts.placeOf(ru, FamilyKind.Series, m))
        assertEquals("S2 E5 from 17:00", FamilyTexts.jumpLabel(en, FamilyKind.Series, m))
        assertEquals("С2 Э5 с 17:00", FamilyTexts.jumpLabel(ru, FamilyKind.Series, m))
        // The next episode is not started: it says what is next and just plays it.
        val next = member(next = true, season = 1, episode = 3)
        assertEquals("Up next: S1 E3", FamilyTexts.placeOf(en, FamilyKind.Series, next))
        assertEquals("Далее: С1 Э3", FamilyTexts.placeOf(ru, FamilyKind.Series, next))
        assertEquals("Play S1 E3", FamilyTexts.jumpLabel(en, FamilyKind.Series, next))
        assertEquals("Смотреть С1 Э3", FamilyTexts.jumpLabel(ru, FamilyKind.Series, next))
        assertEquals("Special 1", FamilyTexts.code(en, member(season = 0, episode = 1)))
        assertEquals("Спецвыпуск 1", FamilyTexts.code(ru, member(season = 0, episode = 1)))
    }

    @Test
    fun aFilmSaysHowMuchOfItIsSeen() {
        val m = member(positionMs = 34 * 60_000, durationMs = 110 * 60_000)
        assertEquals("34 min of 1 h 50 min", FamilyTexts.placeOf(en, FamilyKind.Movie, m))
        assertEquals("34 мин из 1 ч 50 мин", FamilyTexts.placeOf(ru, FamilyKind.Movie, m))
        assertEquals("Play from 34:00", FamilyTexts.jumpLabel(en, FamilyKind.Movie, m))
        assertEquals("Смотреть с 34:00", FamilyTexts.jumpLabel(ru, FamilyKind.Movie, m))
        // Under half a minute is a clock, not «0 мин».
        assertEquals("0:20 of 1 h 50 min", FamilyTexts.placeOf(en, FamilyKind.Movie, member(positionMs = 20_000, durationMs = 110 * 60_000)))
        // Without a length: the minutes alone.
        assertEquals("12 min", FamilyTexts.placeOf(en, FamilyKind.Movie, member(positionMs = 12 * 60_000)))
        assertEquals("", FamilyTexts.jumpLabel(en, FamilyKind.Movie, member()))
    }

    @Test
    fun finishedIsSaidInTheWordsOfTheKind() {
        val done = member(finished = true)
        assertEquals("All watched", FamilyTexts.placeOf(en, FamilyKind.Series, done))
        assertEquals("Всё просмотрено", FamilyTexts.placeOf(ru, FamilyKind.Series, done))
        assertEquals("Watched", FamilyTexts.placeOf(en, FamilyKind.Movie, done))
        assertEquals("Просмотрено", FamilyTexts.placeOf(ru, FamilyKind.Episode, done))
        assertEquals("Прочитано", FamilyTexts.placeOf(ru, FamilyKind.Comic, done))
        assertEquals("Прослушано", FamilyTexts.placeOf(ru, FamilyKind.Audiobook, done))
        assertEquals("", FamilyTexts.jumpLabel(en, FamilyKind.Series, done))
    }

    @Test
    fun anAudiobookSaysTheChapterWhenItHasThem() {
        val chapters = listOf(0L, 60_000L, 120_000L).mapIndexed { idx, start -> Chapter(idx = idx, startMs = start, endMs = start + 60_000) }
        val m = member(positionMs = 90_000, durationMs = 180_000, fraction = 0.5)
        assertEquals("Chapter 2 · 50%", FamilyTexts.placeOf(en, FamilyKind.Audiobook, m, chapters))
        assertEquals("Глава 2 · 50 %", FamilyTexts.placeOf(ru, FamilyKind.Audiobook, m, chapters))
        assertEquals("50%", FamilyTexts.placeOf(en, FamilyKind.Audiobook, m))
        assertEquals("Listen from 1:30", FamilyTexts.jumpLabel(en, FamilyKind.Audiobook, m))
        assertEquals("Слушать с 1:30", FamilyTexts.jumpLabel(ru, FamilyKind.Audiobook, m))
    }

    @Test
    fun aReaderSaysThePageOrHowFarOn() {
        assertEquals("p. 120 of 300", FamilyTexts.placeOf(en, FamilyKind.Book, member(locator = "page:120", page = 120, pages = 300, fraction = 0.4)))
        assertEquals("стр. 120 из 300", FamilyTexts.placeOf(ru, FamilyKind.Book, member(locator = "page:120", page = 120, pages = 300, fraction = 0.4)))
        assertEquals("p. 7", FamilyTexts.placeOf(en, FamilyKind.Book, member(locator = "page:7", page = 7)))
        assertEquals("42%", FamilyTexts.placeOf(en, FamilyKind.Book, member(locator = "epubcfi(/6/4)", fraction = 0.42)))
        assertEquals("Читать со стр. 120", FamilyTexts.jumpLabel(ru, FamilyKind.Book, member(locator = "page:120", page = 120)))
        assertEquals("Read from 42%", FamilyTexts.jumpLabel(en, FamilyKind.Book, member(locator = "epubcfi(/6/4)", fraction = 0.42)))
    }

    @Test
    fun aComicSaysTheVolumeAndPage() {
        assertEquals("Vol. 3 · p. 12 of 40", FamilyTexts.placeOf(en, FamilyKind.Comic, member(volume = 3.0, page = 12, pages = 40)))
        assertEquals("Том 3 · стр. 12 из 40", FamilyTexts.placeOf(ru, FamilyKind.Comic, member(volume = 3.0, page = 12, pages = 40)))
        assertEquals("Vol. 2.5", FamilyTexts.volume(en, member(volume = 2.5)))
        assertEquals("Annual", FamilyTexts.volume(en, member(title = "Annual")))
        assertEquals("Read Vol. 4", FamilyTexts.jumpLabel(en, FamilyKind.Comic, member(volume = 4.0, next = true)))
        assertEquals("Читать: Том 4", FamilyTexts.jumpLabel(ru, FamilyKind.Comic, member(volume = 4.0, next = true)))
        assertEquals("Read from p. 12", FamilyTexts.jumpLabel(en, FamilyKind.Comic, member(volume = 3.0, page = 12)))
    }

    @Test
    fun theFoldedLineNamesTheMostRecentProfile() {
        val first = member(positionMs = 17 * 60_000, season = 1, episode = 2)
        assertEquals("Sam · S1 E2 · 17 min", FamilyTexts.summary(en, FamilyKind.Series, listOf(first, member())))
        assertEquals("", FamilyTexts.summary(en, FamilyKind.Series, emptyList()))
        assertEquals("Play from 5:00 — from Sam’s place", FamilyTexts.jumpDescription(en, "Play from 5:00", first))
        assertEquals("Смотреть с 5:00 — с места профиля «Sam»", FamilyTexts.jumpDescription(ru, "Смотреть с 5:00", first))
    }
}

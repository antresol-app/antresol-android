package app.antresol.core.playback

import app.antresol.core.model.SubTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLanguagesTest {
    @Test
    fun languageMatchesTwoAndThreeLetterCodes() {
        assertTrue(SubtitleLanguages.sameLanguage("en", "en"))
        assertTrue(SubtitleLanguages.sameLanguage("eng", "en"))
        assertTrue(SubtitleLanguages.sameLanguage("rus", "ru"))
        assertTrue(SubtitleLanguages.sameLanguage("RU", "ru"))
        assertTrue(SubtitleLanguages.sameLanguage("ger", "de"))
        assertTrue(SubtitleLanguages.sameLanguage("fre", "fr"))
        assertTrue(SubtitleLanguages.sameLanguage("chi", "zh"))
        assertFalse(SubtitleLanguages.sameLanguage("ger", "fr"))
        assertFalse(SubtitleLanguages.sameLanguage("rus", "en"))
        assertFalse(SubtitleLanguages.sameLanguage("und", "en"))
        assertFalse(SubtitleLanguages.sameLanguage("", "en"))
    }

    @Test
    fun preferredSkipsImageAndForcedTracks() {
        val image = SubTrack(index = 2, lang = "eng")
        val forced = SubTrack(index = 3, lang = "eng", forced = true, url = "/forced.vtt")
        val full = SubTrack(index = 4, lang = "en", url = "/full.vtt")
        val russian = SubTrack(index = 5, lang = "rus", url = "/ru.vtt")
        val tracks = listOf(image, forced, full, russian)
        assertEquals(full, SubtitleLanguages.preferred(tracks, "en"))
        assertEquals(forced, SubtitleLanguages.preferred(listOf(image, forced), "en"))
        assertEquals(russian, SubtitleLanguages.preferred(tracks, "ru"))
        assertNull(SubtitleLanguages.preferred(tracks, "de"))
        assertNull(SubtitleLanguages.preferred(tracks, ""))
    }
}

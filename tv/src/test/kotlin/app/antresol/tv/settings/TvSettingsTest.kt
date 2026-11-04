package app.antresol.tv.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSettingsTest {
    @Test
    fun subtitleLanguageMatchesTwoAndThreeLetterCodes() {
        assertTrue(TvSettings.sameLanguage("en", "en"))
        assertTrue(TvSettings.sameLanguage("eng", "en"))
        assertTrue(TvSettings.sameLanguage("rus", "ru"))
        assertTrue(TvSettings.sameLanguage("RU", "ru"))
        assertFalse(TvSettings.sameLanguage("rus", "en"))
        assertFalse(TvSettings.sameLanguage("und", "en"))
        assertFalse(TvSettings.sameLanguage("", "en"))
    }
}

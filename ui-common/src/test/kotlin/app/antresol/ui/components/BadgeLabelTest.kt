package app.antresol.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The audio and subtitles words of the server's badges become icons (web ui/Badge.tsx). */
class BadgeLabelTest {
    @Test
    fun audioAndSubtitlesBecomeIcons() {
        assertLabel(BadgeLabel.Kind.Audio, "RU EN", BadgeLabel.of("Audio RU EN"))
        assertLabel(BadgeLabel.Kind.Audio, "RU EN", BadgeLabel.of("Звук RU EN"))
        assertLabel(BadgeLabel.Kind.Subtitles, "RU EN", BadgeLabel.of("Subtitles RU EN"))
        assertLabel(BadgeLabel.Kind.Subtitles, "RU", BadgeLabel.of("Субтитры: RU"))
    }

    /** Subtitles without languages is an icon and its word, not a bare icon. */
    @Test
    fun aWordWithoutLanguagesKeepsTheWord() {
        val english = BadgeLabel.of("Subtitles")
        assertLabel(BadgeLabel.Kind.Subtitles, "", english)
        assertEquals("Subtitles", english.shown)
        assertEquals("Субтитры", BadgeLabel.of("Субтитры").shown)
        assertEquals("Audio", BadgeLabel.of("Audio").shown)
        assertEquals("Звук", BadgeLabel.of("Звук").shown)
        // With languages the word stays an icon alone.
        assertEquals("RU EN", BadgeLabel.of("Subtitles RU EN").shown)
    }

    private fun assertLabel(kind: BadgeLabel.Kind, rest: String, label: BadgeLabel) {
        assertEquals(kind, label.kind)
        assertEquals(rest, label.rest)
    }

    @Test
    fun otherBadgesStayText() {
        for (text in listOf("18+", "HD", "Audiobook", "FLAC 24/96", "Звуковая дорожка")) {
            assertEquals(null, BadgeLabel.of(text).kind)
            assertEquals(text, BadgeLabel.of(text).shown)
        }
    }
}

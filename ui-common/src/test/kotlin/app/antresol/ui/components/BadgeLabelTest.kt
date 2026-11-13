package app.antresol.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The audio and subtitles words of the server's badges become icons (web ui/Badge.tsx). */
class BadgeLabelTest {
    @Test
    fun audioAndSubtitlesBecomeIcons() {
        assertEquals(BadgeLabel(BadgeLabel.Kind.Audio, "RU EN"), BadgeLabel.of("Audio RU EN"))
        assertEquals(BadgeLabel(BadgeLabel.Kind.Audio, "RU EN"), BadgeLabel.of("Звук RU EN"))
        assertEquals(BadgeLabel(BadgeLabel.Kind.Subtitles, "RU EN"), BadgeLabel.of("Subtitles RU EN"))
        assertEquals(BadgeLabel(BadgeLabel.Kind.Subtitles, "RU"), BadgeLabel.of("Субтитры: RU"))
    }

    @Test
    fun otherBadgesStayText() {
        for (text in listOf("18+", "HD", "Audiobook", "FLAC 24/96", "Звуковая дорожка")) {
            assertEquals(BadgeLabel(null, text), BadgeLabel.of(text))
        }
    }
}

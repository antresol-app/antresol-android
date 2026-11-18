package app.antresol.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The watched look is one number on every platform: half strength, 0.85 while chosen. */
class WatchedStyleTest {
    @Test
    fun anUnwatchedCardIsNeverDimmed() {
        assertEquals(1f, WatchedStyle.artworkAlpha(watched = false), 0f)
        assertEquals(1f, WatchedStyle.artworkAlpha(watched = false, focused = true), 0f)
    }

    @Test
    fun aWatchedCardIsHalfStrengthAndLiftsWhenFocused() {
        assertEquals(0.5f, WatchedStyle.artworkAlpha(watched = true), 0f)
        assertEquals(0.85f, WatchedStyle.artworkAlpha(watched = true, focused = true), 0f)
    }
}

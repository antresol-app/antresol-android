package app.antresol.mobile.player

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Back on the live player closes the sheet, then the channel strip, and only then leaves: with
 * nothing open no handler is on, so the system's own Back (and its preview) finishes the activity.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiveBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var guide = false
    private var menu = false
    private val exits get() = if (compose.activity.isFinishing) 1 else 0

    private fun setUp(guideOpen: Boolean, menuOpen: Boolean) {
        compose.setContent {
            var g by remember { mutableStateOf(guideOpen) }
            var m by remember { mutableStateOf(menuOpen) }
            guide = g
            menu = m
            LiveBackHandler(
                guideOpen = g,
                menuOpen = m,
                onCloseMenu = { m = false },
                onCloseGuide = { g = false },
            )
        }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun backWithTheChannelListOpenClosesOnlyTheList() {
        setUp(guideOpen = true, menuOpen = false)
        back()
        assertFalse(guide)
        assertEquals(0, exits)
        back()
        assertEquals(1, exits)
    }

    @Test
    fun backWithATrackSheetOpenClosesOnlyTheSheet() {
        setUp(guideOpen = true, menuOpen = true)
        back()
        assertFalse(menu)
        assertTrue(guide)
        assertEquals(0, exits)
        back()
        assertFalse(guide)
        assertEquals(0, exits)
        back()
        assertEquals(1, exits)
    }

    @Test
    fun backWithNothingOpenLeavesThePlayer() {
        setUp(guideOpen = false, menuOpen = false)
        back()
        assertEquals(1, exits)
    }
}

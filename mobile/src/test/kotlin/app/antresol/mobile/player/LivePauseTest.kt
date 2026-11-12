package app.antresol.mobile.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.ui.theme.AntresolTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The phone's live controls: the centre play/pause says what it does (Pause while playing, Play
 * while paused) and toggles; «LIVE» at the edge, «Go Live» more than 10 s behind it, which jumps.
 */
@RunWith(AndroidJUnit4::class)
class LivePauseTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land")
    fun theCentreButtonPausesAndPlays() {
        var toggles = 0
        compose.setContent {
            var paused by remember { mutableStateOf(false) }
            AntresolTheme { Box(Modifier.fillMaxSize()) { LivePlayPause(paused) { paused = !paused; toggles++ } } }
        }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        assertEquals(2, toggles)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w640dp-h360dp-land")
    fun liveAtTheEdgeGoLiveBehindIt() {
        var behind by mutableStateOf(false)
        var jumps = 0
        compose.setContent { AntresolTheme { LiveEdgeMark(behind) { jumps++ } } }
        compose.onNodeWithText("LIVE").assertIsDisplayed()
        behind = true
        compose.onNodeWithTag("live-go-live").assertIsDisplayed()
        compose.onNodeWithText("Go Live").performClick()
        assertEquals(1, jumps)
    }
}

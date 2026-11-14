package app.antresol.tv.ui.components

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.tv.AntresolTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The remote's Back on an open [OptionsPanel] (a real KEYCODE_BACK through the window, as on the TV,
 * not `onBackPressedDispatcher.onBackPressed()`): the panel closes, nothing is chosen, the screen's
 * own Back does not run, and focus goes back to the button that opened it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
class OptionsPanelBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backClosesThePanelWithoutChoosing() = backCloses(callerBackHandler = false)

    /** The screens also have their own `BackHandler(menu != null)`: the result must be the same. */
    @Test
    fun backClosesThePanelWithACallerBackHandler() = backCloses(callerBackHandler = true)

    private fun backCloses(callerBackHandler: Boolean) {
        var open by mutableStateOf(false)
        var chosen by mutableIntStateOf(1)
        var screenBacks = 0
        compose.setContent {
            AntresolTvTheme {
                val opener = remember { FocusRequester() }
                var wasOpen by remember { mutableStateOf(false) }
                LaunchedEffect(open) {
                    if (open) wasOpen = true else if (wasOpen) opener.tryRequestFocus()
                }
                LaunchedEffect(Unit) { opener.tryRequestFocus() }
                // The screen's Back (leave the page), registered before the panel's.
                BackHandler { screenBacks++ }
                if (callerBackHandler) BackHandler(enabled = open) { open = false }
                Box {
                    Column {
                        PillButton("Other", onClick = {})
                        PillButton("Quality", onClick = { open = true }, modifier = Modifier.focusRequester(opener).testTag("opener"))
                    }
                    if (open) {
                        OptionsPanel(
                            title = "Video Quality",
                            options = listOf("Auto", "1080p", "720p").mapIndexed { i, label ->
                                MenuOption(label, i == chosen) { chosen = i }
                            },
                            onDismiss = { open = false },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("opener").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Video Quality").assertExists()
        key(KeyEvent.KEYCODE_DPAD_DOWN) // focus moves inside the panel, still nothing chosen

        key(KeyEvent.KEYCODE_BACK)

        compose.onNodeWithText("Video Quality").assertDoesNotExist()
        assertEquals("Back must not choose an option", 1, chosen)
        assertEquals("the screen's Back must not run while a panel is open", 0, screenBacks)
        compose.onNodeWithTag("opener").assertIsFocused()

        // With the panel closed, Back is the screen's again.
        key(KeyEvent.KEYCODE_BACK)
        assertEquals(1, screenBacks)
    }

    private fun key(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }
}

package app.antresol.tv.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.tv.AntresolTvTheme
import app.antresol.tv.ui.components.MenuOption
import app.antresol.tv.ui.components.OptionsPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The film player's control row: round icon buttons with only the focused control looking focused
 * (the Play button used to stay a white pill while the seek bar had focus), the name of the focused
 * button under it, D-pad order, and the remote's Back on an open track menu.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerControlsFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shots = File("build/screenshots").apply { mkdirs() }
    private var quality by mutableIntStateOf(0)
    private var playing by mutableStateOf(true)
    private var exits = 0

    private fun show() {
        compose.setContent {
            AntresolTvTheme {
                // The player route's own Back (leave the player), registered before the overlay's.
                BackHandler { exits++ }
                PlayingOverlayContent(
                    transport = TransportState(
                        positionMs = { 754_000 }, bufferedMs = { 900_000 }, durationMs = 5_400_000, playing = playing,
                        audioChoice = true, subtitleChoice = true, nextEpisode = false,
                    ),
                    // Paused-looking for the clock: the controls must not fade during the test.
                    isPlaying = false,
                    busy = false,
                    title = {},
                    actions = TransportActions(
                        onSeek = {}, onToggle = { playing = !playing }, onNext = {},
                        onScrubKey = { false }, onMediaKey = { false }, onHiddenKey = { _, _ -> false },
                    ),
                    menuContent = { menu, onDismiss ->
                        OptionsPanel(
                            title = "Menu $menu",
                            options = listOf("Auto", "1080p", "720p").mapIndexed { i, label -> MenuOption(label, i == quality) { quality = i } },
                            onDismiss = onDismiss,
                        )
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun onlyTheFocusedControlLooksFocused() {
        show()
        val seek = compose.onNodeWithTag(PlayerTags.SEEK_BAR)
        val play = compose.onNodeWithTag(PlayerTags.PLAY_PAUSE)
        seek.assertIsFocused()
        play.assertIsNotFocused()
        shot("player-focus-seek")
        // At rest the Play button is dark glass like the others, not a white "focused" pill.
        assertTrue("Play looks focused while the seek bar has focus", centreLuminance(play) < 0.5f)
        assertTrue(centreLuminance(compose.onNode(hasContentDescription("Quality"))) < 0.5f)
        // No caption while no button has focus.
        compose.onNodeWithText("Pause").assertDoesNotExist()

        key(KeyEvent.KEYCODE_DPAD_DOWN)
        play.assertIsFocused()
        assertTrue("the focused button is white", centreLuminance(play) > 0.8f)
        compose.onNodeWithText("Pause").assertExists() // its caption, and only its
        compose.onNodeWithText("Audio").assertDoesNotExist()
        shot("player-focus-play")

        val names = listOf("Audio", "Subtitles", "Quality")
        names.forEach { name ->
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNode(hasContentDescription(name)).assertIsFocused()
            assertTrue("$name is white when focused", centreLuminance(compose.onNode(hasContentDescription(name))) > 0.8f)
            assertTrue("Play is glass again", centreLuminance(play) < 0.5f)
            names.filter { it != name }.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
            compose.onNodeWithText(name).assertExists()
            shot("player-focus-${name.lowercase()}")
        }

        // Up goes back to the seek bar; OK on Play toggles its icon and name.
        key(KeyEvent.KEYCODE_DPAD_UP)
        seek.assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        play.assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(false, playing)
        compose.onNodeWithText("Play").assertExists()
    }

    @Test
    fun backClosesATrackMenuWithoutChoosing() {
        show()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 3)
        val opener = compose.onNode(hasContentDescription("Quality"))
        opener.assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Menu Quality").assertExists()
        key(KeyEvent.KEYCODE_DPAD_DOWN) // move inside the menu, choose nothing
        compose.onNode(isFocused() and hasContentDescriptionOrText("1080p")).assertExists()

        key(KeyEvent.KEYCODE_BACK)

        compose.onNodeWithText("Menu Quality").assertDoesNotExist()
        assertEquals("Back must not pick a quality", 0, quality)
        assertEquals("Back on the menu must not leave the player", 0, exits)
        compose.onNodeWithTag(PlayerTags.SEEK_BAR).assertExists() // controls still up
        opener.assertIsFocused()

        // Then Back hides the controls, then leaves.
        key(KeyEvent.KEYCODE_BACK)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag(PlayerTags.SEEK_BAR).assertDoesNotExist()
        assertEquals(0, exits)
        key(KeyEvent.KEYCODE_BACK)
        assertEquals(1, exits)
    }

    private fun hasContentDescriptionOrText(value: String) =
        androidx.compose.ui.test.hasText(value) or androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText(value))

    /** Luminance (0–1) of the button's fill: a point between its edge and its glyph, on screen. */
    private fun centreLuminance(node: SemanticsNodeInteraction): Float {
        compose.mainClock.advanceTimeBy(500) // the focus animation
        compose.waitForIdle()
        val b = node.fetchSemanticsNode().boundsInWindow
        val pixel = screen().getPixel((b.left + b.width / 6).toInt(), b.center.y.toInt())
        val r = android.graphics.Color.red(pixel) / 255f
        val g = android.graphics.Color.green(pixel) / 255f
        val bl = android.graphics.Color.blue(pixel) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * bl
    }

    private fun screen(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun key(code: Int, times: Int = 1) = repeat(times) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        File(shots, "$name.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

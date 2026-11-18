package app.antresol.tv.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.playback.SkipAction
import app.antresol.core.playback.SkipNote
import app.antresol.core.playback.SkipPill
import app.antresol.tv.AntresolTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The skip pills of the film player: «Пропустить вступление» with the controls up and alone, OK
 * pressing it while the controls are hidden, «Следующая серия» counting down at the credits, a pill
 * past its 8 s leaving OK to pause, and «Вернуть» after an automatic skip. Screenshots go to
 * `tv/build/screenshots/skip-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "ru-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkipPillsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shots = File("build/screenshots").apply { mkdirs() }
    private var pill by mutableStateOf<SkipPill?>(null)
    private var note by mutableStateOf<SkipNote?>(null)
    private var presses = 0
    private var watchCredits = 0
    private var undos = 0
    private var toggles = 0

    private fun show() {
        compose.setContent {
            AntresolTvTheme {
                PlayingOverlayContent(
                    transport = TransportState(
                        positionMs = { 75_000 }, bufferedMs = { 200_000 }, durationMs = 2_600_000, playing = true,
                        audioChoice = true, subtitleChoice = true, nextEpisode = true, autoSkipChoice = true,
                    ),
                    // Paused-looking for the clock: the controls must not fade during the test.
                    isPlaying = false,
                    busy = false,
                    title = {},
                    actions = TransportActions(
                        onSeek = {}, onToggle = { toggles++ }, onNext = {},
                        onScrubKey = { false }, onMediaKey = { false },
                        onHiddenKey = { event, show ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) toggles++
                            if (event.type == KeyEventType.KeyDown) show()
                            event.key == Key.DirectionCenter
                        },
                    ),
                    menuContent = { _, _ -> },
                    skip = SkipOverlay(pill, note, onPress = { presses++ }, onWatchCredits = { watchCredits++ }, onUndo = { undos++ }),
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun introPillWithTheControlsAndAloneTakesOk() {
        pill = SkipPill.Skip("intro", alone = true, action = SkipAction.Seek(150_000))
        show()
        compose.onNodeWithTag(SkipTags.SKIP).assertExists()
        compose.onNodeWithText("Пропустить вступление").assertExists()
        compose.onNodeWithTag(PlayerTags.SEEK_BAR).assertIsFocused()
        shot("skip-intro-controls")

        key(KeyEvent.KEYCODE_BACK) // hides the controls
        compose.onNodeWithTag(PlayerTags.SEEK_BAR).assertDoesNotExist()
        compose.onNodeWithTag(SkipTags.SKIP).assertIsFocused()
        shot("skip-intro-alone")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, presses)
        assertEquals("OK on the pill must not pause", 0, toggles)
    }

    @Test
    fun aPillPastItsEightSecondsShowsOnlyWithTheControls() {
        pill = SkipPill.Skip("recap", alone = false, action = SkipAction.Seek(40_000))
        show()
        compose.onNodeWithText("Пропустить пересказ").assertExists()
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag(SkipTags.SKIP).assertDoesNotExist()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("OK pauses: no pill on screen", 1, toggles)
        assertEquals(0, presses)
    }

    @Test
    fun nextEpisodeAtTheCreditsCountsDown() {
        pill = SkipPill.Next(fill = 0.4f, countdown = true)
        show()
        compose.onNodeWithText("Смотреть титры").assertExists()
        shot("skip-next-controls")
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag(SkipTags.NEXT).assertIsFocused()
        shot("skip-next-alone")
        // Left goes to «Смотреть титры»
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag(SkipTags.WATCH_CREDITS).assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, watchCredits)
        assertEquals(0, presses)
    }

    @Test
    fun undoAfterAnAutomaticSkip() {
        note = SkipNote("intro", backMs = 60_000)
        show()
        compose.onNodeWithText("Вступление пропущено").assertExists()
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag(SkipTags.UNDO).assertIsFocused()
        shot("skip-note")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, undos)
        // the note goes: focus returns to the overlay, OK pauses again
        note = null
        compose.waitForIdle()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, toggles)
    }

    private fun key(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

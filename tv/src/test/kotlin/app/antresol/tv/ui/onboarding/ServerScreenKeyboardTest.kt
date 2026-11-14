package app.antresol.tv.ui.onboarding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.tv.AntresolTvApp
import app.antresol.tv.AntresolTvTheme
import app.antresol.tv.ui.components.TvKeyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The server address on a TV whose keyboard (Gboard for TV: a floating panel over the middle and
 * bottom of the screen) would cover a centred field. Robolectric has no keyboard and reports no
 * IME insets, which is exactly the floating-keyboard case: OK on the field (the field's own
 * `keyboard.show()`) must move it to the top part of the screen, and a D-pad key after the keyboard
 * is gone must bring the page back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerScreenKeyboardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()
    private val shots = File("build/screenshots").apply { mkdirs() }

    @Before
    @After
    fun resetKeyboard() {
        TvKeyboard.requested = false
        TvKeyboard.insetsSeen = false
    }

    /**
     * Robolectric has no keyboard and no IME in the insets: the floating-keyboard case. OK on the
     * field (its `keyboard.show()`) must move the field to the top third, and a D-pad key reaching
     * the field (the keyboard is gone, it no longer takes the D-pad) must bring the page back.
     */
    @Test
    fun theFieldRidesAboveTheKeyboardAndComesBack() {
        compose.setContent { AntresolTvTheme { ServerScreen(app.session) } }
        settle()
        val height = compose.activity.resources.displayMetrics.heightPixels.toFloat()
        val field = compose.onNode(hasSetTextAction())
        val rest = field.fetchSemanticsNode().boundsInRoot
        shot("server-rest")
        assertTrue("at rest the field is in the middle ($rest of $height)", rest.top > height * 0.4f)
        compose.onNodeWithText("Enter the address", substring = true).assertExists()

        key(KeyEvent.KEYCODE_DPAD_CENTER) // OK opens the keyboard
        settle()
        val typing = field.fetchSemanticsNode().boundsInRoot
        assertTrue("while typing the field is in the top third ($typing of $height)", typing.bottom < height / 3)
        compose.onNodeWithText("Enter the address", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Connect to your server").assertExists()
        shot("server-keyboard-open")

        key(KeyEvent.KEYCODE_DPAD_LEFT) // reaches the field: the keyboard is not taking keys any more
        settle()
        assertEquals(false, TvKeyboard.requested)
        assertEquals(rest.top, field.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithText("Enter the address", substring = true).assertExists()

        // Leaving the field with the keyboard up (Down to Connect) restores the page too.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        settle()
        assertTrue(field.fetchSemanticsNode().boundsInRoot.bottom < height / 3)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        settle()
        assertEquals(rest.top, field.fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** Gboard ignores autocorrect=false for suggestions: the address must ask for none, as a URI. */
    @Test
    fun theAddressAsksTheKeyboardForNoSuggestions() {
        compose.setContent { AntresolTvTheme { ServerScreen(app.session) } }
        compose.waitForIdle()
        val info = EditorInfo()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView.findFocus()
            checkNotNull(view.onCreateInputConnection(info)) { "the focused field has no input connection" }
        }
        val type = info.inputType
        assertEquals(InputType.TYPE_CLASS_TEXT, type and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_URI, type and InputType.TYPE_MASK_VARIATION)
        assertTrue("no suggestions", type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertTrue("no autocorrect", type and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT == 0)
    }

    private fun key(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

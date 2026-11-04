package app.antresol.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.session.AuthState
import app.antresol.tv.ui.root.AntresolTvRoot
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real UI against a live server with D-pad key events and saves screenshots to
 * `tv/build/screenshots`. Runs only with `ANTRESOL_TEST_SERVER` set ("1" = http://127.0.0.1:8420);
 * signs in as profile 1 with `ANTRESOL_TEST_PASSWORD` (default `admin123`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvLiveUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val serverUrl: String? = System.getenv("ANTRESOL_TEST_SERVER")?.let { if (it == "1") "http://127.0.0.1:8420" else it }
    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()
    private val screenshots = File("build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        assumeTrue("ANTRESOL_TEST_SERVER not set", serverUrl != null)
    }

    @Test
    fun onboardingHomeFocusAndNavigation() {
        val session = app.session
        runBlocking {
            session.restore()
            if (session.state.value is AuthState.SignedIn) session.switchProfile()
            if (session.state.value !is AuthState.NoServer) session.changeServer()
        }
        compose.setContent { AntresolTvTheme { AntresolTvRoot(session) } }

        // Server address: a bad address shows an error, the real one opens the profile picker.
        waitFor(hasText("Connect to your server"))
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("127.0.0.1:1")
        field.performImeAction()
        waitFor(hasText("Can’t reach the server", substring = true))
        shot("00-server-error")
        field.performTextReplacement(serverUrl!!.removePrefix("http://"))
        field.performImeAction()

        // Profile picker: the first profile (Admin, password) is focused; OK asks for the password.
        waitFor(hasText("Who’s watching?"))
        waitFor(isFocused() and hasAnyDescendant(hasContentDescription("Admin")))
        shot("01-profiles")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Enter the password for Admin"))
        compose.onNode(hasSetTextAction()).performTextReplacement(System.getenv("ANTRESOL_TEST_PASSWORD") ?: "admin123")
        shot("01b-password")
        compose.onNode(hasSetTextAction()).performImeAction()

        // The shell opens with the hero Play button focused.
        waitFor(isFocused() and (hasText("Play") or hasText("Resume") or hasAnyDescendant(hasText("Play") or hasText("Resume"))))
        Thread.sleep(3000) // artwork
        shot("02-home")

        // Down moves into the first shelf, Down again into the second; OK opens that card's detail page.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        val first = focusedTitle()
        key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 8) // past the end of the row: focus must stay in the row
        val lastInRow = focusedTitle()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        val second = focusedTitle()
        check(second != lastInRow) { "Down did not move focus ($second)" }
        shot("03-home-shelf")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(isFocused().not() and hasText(second))
        Thread.sleep(1500)
        shot("04-detail")

        // Back restores focus to the same card.
        key(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { runCatching { focusedTitle() == second }.getOrDefault(false) }

        // Rows remember their focused card: move right in a row, down, then up again.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val remembered = focusedTitle()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_UP)
        check(focusedTitle() == remembered) { "Row focus not restored: ${focusedTitle()} != $remembered" }

        // Back again returns to the hero Play button; Up then reaches the nav on the current tab.
        key(KeyEvent.KEYCODE_BACK)
        waitFor(isFocused() and (hasText("Play") or hasText("Resume") or hasAnyDescendant(hasText("Play") or hasText("Resume"))))
        key(KeyEvent.KEYCODE_DPAD_UP)
        waitFor(isFocused() and hasAnyDescendant(hasText("Home")))

        // Right in the nav → Movies; OK opens the Movies grid.
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor(isFocused() and hasAnyDescendant(hasText("Movies")))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Recently Added"))
        Thread.sleep(1000)
        shot("05-movies")
        // Down enters the grid (the sort pills sit above the cards).
        key(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)
        compose.waitUntil(5_000) { runCatching { focusedTitle() }.isSuccess }
        // Up to the nav lands on the current tab; Right twice to Music, whose first card gets focus.
        key(KeyEvent.KEYCODE_DPAD_UP, times = 2)
        waitFor(isFocused() and hasAnyDescendant(hasText("Movies")))
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(15_000) { runCatching { focusedTitle() }.isSuccess }
        Thread.sleep(1000)
        shot("06-music")

        runBlocking { session.switchProfile() }
        waitFor(hasText("Who’s watching?"))
    }

    private fun key(code: Int, times: Int = 1) = repeat(times) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
    }

    /** The title of the focused card (artwork description, or the placeholder title), from the merged semantics. */
    private fun focusedTitle(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.firstOrNull()
            ?: node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.firstOrNull()?.text
            ?: error("Focused node has no title: ${node.config}")
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(screenshots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

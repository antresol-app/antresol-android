package app.antresol.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.fixtures.FakeServer
import app.antresol.tv.ui.shell.MainShell
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The signed-in shell against [FakeServer] at the design canvas (960 dp, xhdpi) and at what many 1080p TVs
 * report (about 1442 dp, tvdpi): the pages are laid out the same, in canvas dp (px × 960 / window width),
 * because the app normalises the density once at its root. Screenshots go to `$DENSITY_SHOTS_DIR`
 * (default `tv/build/density-shots`).
 */
abstract class TvDensityBase(private val density: String) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()
    private val shots = File(System.getenv("DENSITY_SHOTS_DIR") ?: "build/density-shots").apply { mkdirs() }

    @Before
    fun setUp() {
        fake.start()
        val session = app.session
        runBlocking {
            session.restore()
            if (session.state.value !is AuthState.NoServer) session.changeServer()
            session.connect(fake.baseUrl)
            session.signIn(Profile(1, "Alex", "grad-3"), null)
        }
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).build())
        compose.setContent {
            val state by session.state.collectAsState()
            (state as? AuthState.SignedIn)?.let { AntresolTvTheme { MainShell(session, it) } }
        }
        waitFor(hasText("The Test Show") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        waitFor(isFocused())
    }

    @After
    fun tearDown() {
        fake.shutdown()
    }

    private val scale get() = compose.activity.window.decorView.width / 960f

    @Test
    fun homeFillsTheWindowLikeTheDesign() {
        settleImages()
        shot("home")
        // The hero's Play button starts at the page's left margin (48 canvas dp), whatever the density.
        val play = compose.onAllNodes(hasText("Play") and hasClickAction()).fetchSemanticsNodes().first().boundsInRoot
        assertEquals("Play button left in canvas dp", 48f, play.left / scale, 2f)
    }

    @Test
    fun theMoviesGridHasTheDesignsGuttersAndRowHeight() {
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Movie 1"))
        settleImages()
        shot("movies")
        val cards = compose.onAllNodes(hasClickAction() and hasContentDescription("Movie", substring = true)).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("no movie cards found", cards.isNotEmpty())
        val left = cards.minOf { it.left } / scale
        val right = compose.activity.window.decorView.width / scale - cards.maxOf { it.right } / scale
        assertEquals("left gutter in canvas dp", 48f, left, 2f)
        assertEquals("equal gutters: $left vs $right", left, right, 2f)
        // A poster card is the design's 124 dp wide.
        assertEquals("poster width in canvas dp", 124f, cards.first().width / scale, 3f)
    }

    @Test
    fun theSeriesPageHasTheDesignsHeaderHeight() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER) // Details
        waitFor(hasTestTag("action-trailer"))
        settleImages()
        shot("series")
        // The page's artwork header is 62% of the 540 dp canvas.
        val page = compose.onAllNodes(hasTestTag("page/detail"), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        assertEquals("page height in canvas dp", 540f, page.height / scale, 1f)
    }

    private fun settleImages() {
        repeat(20) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(50)
        }
        compose.waitForIdle()
    }

    private fun key(code: Int, times: Int = 1) = repeat(times) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
        Thread.sleep(150)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) {
                Thread.sleep(10)
                ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            shot("failure")
            throw e
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$density-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** The design canvas: 960x540 dp at xhdpi (1920x1080 px). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvDensityXhdpiTest : TvDensityBase("xhdpi")

/** A common 1080p TV setting: tvdpi (213 dpi), so the screen reports about 1442x811 dp. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w1442dp-h811dp-land-television-tvdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvDensityTvdpiTest : TvDensityBase("tvdpi")

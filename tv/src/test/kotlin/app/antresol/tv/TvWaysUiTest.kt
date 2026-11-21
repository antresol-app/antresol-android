package app.antresol.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.fixtures.FakeServer
import app.antresol.tv.ui.shell.MainShell
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
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
 * The addons' ways on the TV against [FakeServer] speaking the `x/app` protocol with one addon
 * ("alpha"), with D-pad key events: the ways of a library series, the choice step by step with a
 * held OK, the search row of the addon and the page of a title off the library. Screenshots go to
 * `tv/build/screenshots/ways-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvWaysUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()
    private val shots = File("build/screenshots").apply { mkdirs() }

    private fun start(addons: Boolean = true) {
        fake.addons = addons
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
        waitFor(hasText("The Test Show") and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text))
        waitFor(isFocused())
    }

    @After
    fun tearDown() {
        fake.shutdown()
    }

    /** The hero's Details: the series page. */
    private fun openSeries() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT) // hero Play → Details
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("page/detail"))
    }

    @Test
    fun aLibrarySeriesChoosesItsWayStepByStepAndPlaysThroughTheInputPlayer() {
        start()
        openSeries()
        waitFor(hasTestTag("ways-more"))
        settle()
        shot("ways-tv-series")
        // The keep way is for what is not in the library.
        assertTrue(compose.onAllNodes(hasTestTag("way-keep-keep"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        key(KeyEvent.KEYCODE_DPAD_DOWN) // actions → the ways row
        assertFocusedText("More ways to watch")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFocusedText("Watch from a source")
        shot("ways-tv-list")
        key(KeyEvent.KEYCODE_DPAD_CENTER) // a quick run: no remembered choice, so the choice opens
        waitFor(hasText("Source One"))
        assertFocusedText("Source One")
        shot("ways-tv-step-source")
        assertTrue(fake.addonCalls.any { it.startsWith("POST app/run") && it.contains("\"quick\":true") })
        key(KeyEvent.KEYCODE_DPAD_CENTER) // Source One → Quality
        waitFor(hasText("1080p"))
        shot("ways-tv-step-quality")
        // Back returns to the step before; on again to a final row, which runs the way.
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Source Two"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("1080p"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil { fake.requests.contains("POST /api/play/input/in-1") }
        assertTrue(fake.requests.contains("GET /api/play/input/in-1"))
        val run = fake.addonCalls.last { it.startsWith("POST app/run") }
        // The panel starts on its first row after a step back: 1080p.
        assertTrue(run, run.contains("\"ref\":\"q-1080\"") && run.contains("\"tmdbId\":${FakeServer.SERIES_TMDB}"))
        assertTrue("the nav is hidden by the player", compose.onAllNodes(hasText("Movies") and androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().isEmpty())
    }

    /** Hold OK on a way: the choice from its first step, the addon is not asked to decide. */
    @Test
    fun heldOkOnAWayOpensTheChoiceDirectly() {
        fake.wayRemembered = true
        start()
        openSeries()
        waitFor(hasTestTag("ways-more"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFocusedText("Watch from a source")
        hold(KeyEvent.KEYCODE_DPAD_CENTER, repeats = 3)
        waitFor(hasText("Source One"))
        assertTrue("no quick run: the choice was asked for", fake.addonCalls.none { it.startsWith("POST app/run") })
        assertTrue(fake.addonCalls.any { it.startsWith("POST app/step") })
        assertTrue("the held key did not pick a row", fake.addonCalls.count { it.startsWith("POST app/step") } == 1)
    }

    @Test
    fun episodesNotInTheLibraryOfferTheirWaysOnOkAndTheLibraryOnesOnHeldOk() {
        start()
        openSeries()
        waitFor(hasTestTag("ways-more"))
        key(KeyEvent.KEYCODE_DPAD_DOWN) // ways row
        key(KeyEvent.KEYCODE_DPAD_DOWN) // season pills
        key(KeyEvent.KEYCODE_DPAD_DOWN) // episodes
        hold(KeyEvent.KEYCODE_DPAD_CENTER, repeats = 3)
        // Beside the episode's own page: the ways that run for an episode (not the keep way).
        waitFor(hasText("Open Episode Page"))
        assertFocusedText("Open Episode Page")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedText("Watch from a source")
        shot("ways-tv-episode")
        assertTrue(compose.onAllNodes(hasText("Keep on the server"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Source One"))
        val step = fake.addonCalls.first { it.startsWith("POST app/step") }
        // The first episode of the first season, run as an episode of the series.
        assertTrue(step, step.contains("\"season\":1") && step.contains("\"episode\":"))
    }

    @Test
    fun withoutAddonsNothingNewShows() {
        start(addons = false)
        openSeries()
        settle()
        assertTrue(compose.onAllNodes(hasTestTag("ways-more"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(fake.requests.none { it.contains("/x/app") })
    }

    /** Search for "far" and open the title that is not in the library: its page. */
    private fun openTitleOffTheLibrary() {
        key(KeyEvent.KEYCODE_DPAD_UP) // the nav, on Home
        key(KeyEvent.KEYCODE_DPAD_LEFT) // the search pill
        runFor(900)
        key(KeyEvent.KEYCODE_DPAD_DOWN) // into the page: the field
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("far")
        // The library's row first; the addon's row, titled by the addon's own words, below it.
        waitFor(hasText("Movies & Shows"))
        settle()
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) } // the field → the scope pills → the library row → the addon's row
        waitFor(hasText("Find and watch"))
        settle()
        shot("ways-tv-search")
        assertTrue(fake.addonCalls.any { it.startsWith("GET app/search?q=far") })
        key(KeyEvent.KEYCODE_DPAD_RIGHT) // Movie 1 (in the library) → Far Away Film (not)
        waitFor(isFocused() and hasAnyDescendant(hasText("Far Away Film") or androidx.compose.ui.test.hasContentDescription("Far Away Film")))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("way-primary"))
        waitFor(hasTestTag("way-keep-keep"))
        settle()
        shot("ways-tv-title")
        assertTrue(fake.requests.contains("GET /api/title/movie/${FakeServer.OUTSIDE_TMDB}"))
    }

    @Test
    fun searchAddsTheAddonRowAndATitleOffTheLibraryOpensItsPage() {
        fake.keepActive = true
        start()
        openTitleOffTheLibrary()
        // Nothing of the library plays here: the first way is the first button; no watched toggle.
        assertTrue(compose.onAllNodes(hasText("Mark as Watched"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // The keep way is working: its words and «Cancel» behind its button.
        waitFor(hasText("Saved 40%"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedText("Keep on the server")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Cancel"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil { fake.addonCalls.any { it.startsWith("POST app/cancel") } }
        assertFalse(fake.keepActive)
    }

    /** Like the web's «Скачать»: OK on a keep way opens the choice and never runs by itself. */
    @Test
    fun okOnAKeepWayOpensTheChoiceAndRunsFromAFinalRow() {
        start()
        openTitleOffTheLibrary()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedText("Keep on the server")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Source One"))
        shot("ways-tv-keep-step")
        assertTrue("OK asked for the first step, not for a run", fake.addonCalls.none { it.startsWith("POST app/run") })
        key(KeyEvent.KEYCODE_DPAD_CENTER) // Source One → Quality
        waitFor(hasText("1080p"))
        key(KeyEvent.KEYCODE_DPAD_CENTER) // 1080p, final
        waitFor(hasText("Started"))
        val run = fake.addonCalls.last { it.startsWith("POST app/run") }
        assertTrue(run, run.contains("\"way\":\"keep\"") && run.contains("\"ref\":\"q-1080\"") && !run.contains("quick"))
    }

    // region Helpers

    private fun runFor(ms: Long) {
        var left = ms
        while (left > 0) {
            Thread.sleep(8)
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(16)
            left -= 16
        }
        ShadowLooper.idleMainLooper()
    }

    private fun press(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun hold(code: Int, repeats: Int) {
        val down = android.os.SystemClock.uptimeMillis()
        compose.runOnUiThread { compose.activity.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0)) }
        for (i in 1..repeats) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.waitForIdle()
            val flags = if (i == 1) KeyEvent.FLAG_LONG_PRESS else 0
            compose.runOnUiThread { compose.activity.dispatchKeyEvent(KeyEvent(down, down + i * 50, KeyEvent.ACTION_DOWN, code, i, 0, -1, 0, flags)) }
        }
        compose.runOnUiThread { compose.activity.dispatchKeyEvent(KeyEvent(down, down + repeats * 50 + 50, KeyEvent.ACTION_UP, code, 0)) }
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(20) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(50)
        }
        compose.waitForIdle()
    }

    private fun key(code: Int, times: Int = 1) = repeat(times) {
        press(code)
        compose.waitForIdle()
        Thread.sleep(150)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
    }

    private fun assertFocusedText(text: String) {
        waitFor(isFocused() and (hasText(text) or hasAnyDescendant(hasText(text))))
    }

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out; the addon got ${fake.addonCalls}; requests ${fake.requests.takeLast(12)}" }
            Thread.sleep(20)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        }
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) {
                Thread.sleep(10)
                ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
                if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeBy(16)
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            shot("ways-tv-failure")
            throw IllegalStateException("${e.message}; the addon got ${fake.addonCalls}", e)
        }
    }

    private fun shot(name: String) {
        if (compose.mainClock.autoAdvance) compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // endregion
}

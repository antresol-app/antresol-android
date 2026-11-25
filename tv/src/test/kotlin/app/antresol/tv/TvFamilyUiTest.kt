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
import app.antresol.fixtures.FakeServer.FakeFamilyMember
import app.antresol.tv.ui.shell.MainShell
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * «Семья» on the TV title pages against [FakeServer] with other profiles' places, with D-pad key events: a quiet line
 * under the buttons says who is where, OK opens the list, OK on a row takes their position as this profile's own and plays
 * from it; nothing shows when nobody else has progress, and nothing for a kid. Screenshots go to `tv/build/screenshots/family-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvFamilyUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()
    private val shots = File("build/screenshots").apply { mkdirs() }

    private val episodes = FakeServer.EPISODE_IDS

    /** Sam is in the middle of the second episode, Robin has finished the second and not begun the third, Kim is done. */
    private fun seriesFamily() {
        fake.family[FakeServer.SERIES_ID] = listOf(
            FakeFamilyMember(2, "Sam", episodes[1], positionMs = 20 * 60_000, durationMs = 45 * 60_000, season = 1, episode = 2, done = 1, total = 6, agoMinutes = 120),
            FakeFamilyMember(3, "Robin", episodes[2], next = true, season = 1, episode = 3, done = 2, total = 6, agoMinutes = 26 * 60),
            FakeFamilyMember(4, "Kim", episodes[5], finished = true, season = 2, episode = 3, done = 6, total = 6, agoMinutes = 5 * 24 * 60),
        )
    }

    private fun start(kid: Boolean = false) {
        fake.kid = kid
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

    /** Search for «movie» and open the first library result: the page of Movie 1. */
    private fun openMovie() {
        key(KeyEvent.KEYCODE_DPAD_UP) // the nav, on Home
        key(KeyEvent.KEYCODE_DPAD_LEFT) // the search pill
        runFor(900)
        key(KeyEvent.KEYCODE_DPAD_DOWN) // into the page: the field
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("movie")
        waitFor(hasText("Movies & Shows"))
        settle()
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) } // the field → the scope pills → the library row
        waitFor(isFocused() and hasAnyDescendant(hasText("Movie 1") or androidx.compose.ui.test.hasContentDescription("Movie 1")))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("page/detail"))
        waitFor(hasText("Movie 1"))
    }

    /** Down from the buttons to the family line. */
    private fun toTheLine() {
        waitFor(hasTestTag("family-line"))
        repeat(4) {
            if (compose.onAllNodes(isFocused() and hasTestTag("family-line"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
            key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        waitFor(isFocused() and hasTestTag("family-line"))
    }

    @Test
    fun aFilmSaysWhereTheFamilyIsAndContinuesFromTheirPlace() {
        fake.family[FakeServer.MOVIE_ID] = listOf(
            FakeFamilyMember(2, "Sam", FakeServer.MOVIE_ID, positionMs = 34 * 60_000, durationMs = 104 * 60_000, agoMinutes = 120),
        )
        start()
        openMovie()
        waitFor(hasText("FAMILY"))
        waitFor(hasText("Sam · 34 min of 1 h 44 min"))
        settle()
        shot("family-tv-movie")
        toTheLine()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        // The list: who, where and when, and what continuing says; focus on the row there is somewhere to go from.
        waitFor(hasText("Play from 34:00"))
        waitFor(hasText("Pick up where they are — the progress is saved to your profile."))
        assertFocusedText("Sam")
        settle()
        shot("family-tv-panel")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        // Their position is taken as this profile's own, then the player starts: it reads the progress after the copy.
        waitUntil { fake.adopted.contains("${FakeServer.MOVIE_ID} 2") }
        waitUntil { fake.requests.lastIndexOf("GET /api/progress/${FakeServer.MOVIE_ID}") > fake.requests.indexOf("POST /api/progress/${FakeServer.MOVIE_ID}/adopt") }
        assertEquals(34 * 60_000L, fake.progress[FakeServer.MOVIE_ID]?.first)
        // Nothing of theirs was written: the only writes are this profile's adopt.
        assertTrue(fake.requests.none { it.startsWith("PUT /api/progress/") && it.contains("/2") })
    }

    @Test
    fun aSeriesNamesTheMostRecentAndListsEveryEpisodeTheyAreAt() {
        seriesFamily()
        start()
        openSeries()
        waitFor(hasText("Sam · S1 E2 · 20 min · 2 more"))
        settle()
        shot("family-tv-series")
        toTheLine()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("S1 E2 from 20:00"))
        waitFor(hasText("Up next: S1 E3", substring = true))
        waitFor(hasText("All watched", substring = true))
        // Kim has finished everything: nothing to continue, so nothing says so.
        assertTrue(compose.onAllNodes(hasText("Play S2 E3"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertFocusedText("Sam")
        shot("family-tv-series-panel")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedText("Robin")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        // The next episode just starts: nothing to copy.
        waitUntil { fake.requests.contains("GET /api/items/${episodes[2]}") }
        assertTrue(fake.adopted.isEmpty())
    }

    @Test
    fun anEpisodeInProgressIsTakenFromTheSeriesPageToo() {
        seriesFamily()
        start()
        openSeries()
        toTheLine()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("S1 E2 from 20:00"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil { fake.adopted.contains("${episodes[1]} 2") }
        waitUntil { fake.requests.contains("GET /api/items/${episodes[1]}") }
        assertEquals(20 * 60_000L, fake.progress[episodes[1]]?.first)
    }

    @Test
    fun backClosesTheListAndFocusIsOnTheLineAgain() {
        seriesFamily()
        start()
        openSeries()
        toTheLine()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Up next: S1 E3", substring = true))
        key(KeyEvent.KEYCODE_BACK)
        waitFor(isFocused() and hasTestTag("family-line"))
        assertTrue(compose.onAllNodes(hasText("Up next: S1 E3", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(fake.adopted.isEmpty())
    }

    @Test
    fun nothingShowsWhenNobodyElseHasProgress() {
        start()
        openSeries()
        waitFor(hasTestTag("ways-more").or(hasText("Mark as Watched")))
        settle()
        assertTrue(fake.requests.contains("GET /api/items/${FakeServer.SERIES_ID}/family"))
        assertTrue(compose.onAllNodes(hasTestTag("family-line"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("FAMILY"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aKidSeesNobodyThoughTheyHaveProgress() {
        seriesFamily()
        start(kid = true)
        openSeries()
        waitFor(hasText("Mark as Watched"))
        settle()
        assertTrue(compose.onAllNodes(hasTestTag("family-line"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    // region Driving the UI

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
            check(System.currentTimeMillis() < end) { "timed out; requests ${fake.requests.takeLast(14)}" }
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
            shot("family-tv-failure")
            throw IllegalStateException("${e.message}; requests ${fake.requests.takeLast(14)}", e)
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

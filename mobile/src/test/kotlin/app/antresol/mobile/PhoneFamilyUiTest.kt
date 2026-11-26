package app.antresol.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.fixtures.FakeServer
import app.antresol.fixtures.FakeServer.FakeFamilyMember
import app.antresol.mobile.player.VideoPlayerActivity
import app.antresol.mobile.ui.components.LocalPosterZoom
import app.antresol.mobile.ui.shell.MainShell
import app.antresol.ui.theme.AntresolTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * «Семья» on the phone's title pages against [FakeServer] with other profiles' places: a quiet line under the buttons
 * says who is where, a tap opens the sheet, the button on a row takes their position as this profile's own and starts the
 * player from it; nothing shows when nobody else has progress, and nothing for a kid. Screenshots go to
 * `mobile/build/screenshots/family-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneFamilyUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolMobileApp>()
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
        val state = session.state.value as AuthState.SignedIn
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).build())
        // Screenshots draw the view on a software canvas, which the poster zoom's hardware layers cannot be drawn on.
        compose.setContent { CompositionLocalProvider(LocalPosterZoom provides false) { AntresolTheme { MainShell(session, state) } } }
        waitFor(hasText("Continue Watching"))
    }

    @After
    fun tearDown() = fake.shutdown()

    private fun openSeries() {
        list().performScrollToNode(hasText("New Series"))
        compose.onAllNodes(hasText("The Test Show"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("detail-play"))
    }

    private fun openMovie() {
        list().performScrollToNode(hasText("Movie 2"))
        compose.onAllNodes(hasText("Movie 1")).onLast().performClick()
        waitFor(hasTestTag("toggle-watched"))
    }

    @Test
    fun aSeriesNamesTheMostRecentAndTheSheetListsEveryone() {
        seriesFamily()
        start()
        openSeries()
        waitFor(hasTestTag("family-line"))
        waitFor(hasText("Sam · S1 E2 · 20 min"))
        waitFor(hasText("· 2 more", substring = true))
        settle()
        shot("family-phone-series")
        compose.onNode(hasTestTag("family-line")).performClick()
        waitFor(hasTestTag("family-sheet"))
        waitFor(hasText("S1 E2 from 20:00"))
        waitFor(hasText("Up next: S1 E3", substring = true))
        waitFor(hasText("All watched", substring = true))
        // Kim has finished everything: a row without a button.
        assertTrue(compose.onAllNodes(hasTestTag("family-jump-4"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("family-jump-2"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        settle()
        shot("family-phone-sheet")
    }

    @Test
    fun theButtonOnARowTakesTheirPositionThenStartsThePlayerFromIt() {
        seriesFamily()
        start()
        openSeries()
        waitFor(hasTestTag("family-line"))
        compose.onNode(hasTestTag("family-line")).performClick()
        waitFor(hasTestTag("family-jump-2"))
        compose.onNode(hasTestTag("family-jump-2")).performClick()
        waitUntil { fake.adopted.contains("${episodes[1]} 2") }
        assertEquals(20 * 60_000L, fake.progress[episodes[1]]?.first)
        // The player starts for that episode, from its saved place (the copy).
        waitUntil { shadowOf(compose.activity).peekNextStartedActivity() != null }
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(VideoPlayerActivity::class.java.name, started?.component?.className)
        assertEquals(episodes[1], started?.getLongExtra("itemId", -1))
        assertEquals("Resume", started?.getStringExtra("resume"))
        // Nothing of theirs was written.
        assertTrue(fake.requests.none { it.startsWith("PUT /api/progress/") })
    }

    @Test
    fun aNextEpisodeStartsFromItsBeginningWithoutCopyingAnything() {
        seriesFamily()
        start()
        openSeries()
        waitFor(hasTestTag("family-line"))
        compose.onNode(hasTestTag("family-line")).performClick()
        waitFor(hasTestTag("family-jump-3"))
        compose.onNode(hasTestTag("family-jump-3")).performClick()
        waitUntil { shadowOf(compose.activity).peekNextStartedActivity() != null }
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(episodes[2], started?.getLongExtra("itemId", -1))
        assertEquals("FromStart", started?.getStringExtra("resume"))
        assertTrue(fake.adopted.isEmpty())
        assertTrue(fake.requests.none { it.contains("/adopt") })
    }

    @Test
    fun aFilmContinuesFromTheirMinute() {
        fake.family[FakeServer.MOVIE_ID] = listOf(
            FakeFamilyMember(2, "Sam", FakeServer.MOVIE_ID, positionMs = 34 * 60_000, durationMs = 104 * 60_000, agoMinutes = 120),
        )
        start()
        openMovie()
        waitFor(hasText("Sam · 34 min of 1 h 44 min"))
        settle()
        shot("family-phone-movie")
        compose.onNode(hasTestTag("family-line")).performClick()
        waitFor(hasText("Play from 34:00"))
        compose.onNode(hasTestTag("family-jump-2")).performClick()
        waitUntil { fake.adopted.contains("${FakeServer.MOVIE_ID} 2") }
        waitUntil { shadowOf(compose.activity).peekNextStartedActivity() != null }
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(FakeServer.MOVIE_ID, started?.getLongExtra("itemId", -1))
        assertEquals("Resume", started?.getStringExtra("resume"))
    }

    @Test
    fun aRefusedCopyStartsNothingAndSaysSo() {
        // The server no longer has their place (they finished meanwhile): the copy answers 404.
        fake.family[FakeServer.MOVIE_ID] = listOf(
            FakeFamilyMember(2, "Sam", FakeServer.MOVIE_ID, positionMs = 34 * 60_000, durationMs = 104 * 60_000),
        )
        start()
        openMovie()
        waitFor(hasTestTag("family-line"))
        fake.family[FakeServer.MOVIE_ID] = listOf(FakeFamilyMember(2, "Sam", FakeServer.MOVIE_ID, positionMs = 34 * 60_000, durationMs = 104 * 60_000, finished = true))
        compose.onNode(hasTestTag("family-line")).performClick()
        waitFor(hasTestTag("family-jump-2"))
        compose.onNode(hasTestTag("family-jump-2")).performClick()
        waitFor(hasTestTag("family-failed"))
        waitFor(hasText("Couldn’t continue from there. Try again."))
        generateSequence { shadowOf(compose.activity).nextStartedActivity }.forEach {
            assertTrue("no player starts: ${it.component?.className}", it.component?.className != VideoPlayerActivity::class.java.name)
        }
        settle()
        shot("family-phone-failed")
    }

    @Test
    fun nothingShowsWhenNobodyElseHasProgress() {
        start()
        openSeries()
        settle()
        assertTrue(fake.requests.contains("GET /api/items/${FakeServer.SERIES_ID}/family"))
        assertTrue(compose.onAllNodes(hasTestTag("family-line"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aKidSeesNobodyThoughTheyHaveProgress() {
        seriesFamily()
        start(kid = true)
        openSeries()
        settle()
        assertTrue(compose.onAllNodes(hasTestTag("family-line"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    // region Driving the UI

    // The rule's own loop: it moves the test clock a frame at a time, and Compose navigates and starts what comes next
    // only on those frames (a bare looper loop stalls once the server's answer lands after the last key press settled).
    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                condition()
            }
        } catch (e: Throwable) {
            throw IllegalStateException("${e.message}; requests ${fake.requests.takeLast(12)}", e)
        }
    }

    private fun list() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).onFirst()

    private fun settle() {
        repeat(25) {
            Thread.sleep(40)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        compose.waitForIdle()
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 15_000) {
        try {
            compose.waitUntil(timeoutMs) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            shot("family-phone-failure")
            throw IllegalStateException("${e.message}; requests ${fake.requests.takeLast(12)}", e)
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // endregion
}

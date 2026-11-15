package app.antresol.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
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
import app.antresol.mobile.fixtures.FakeServer
import app.antresol.mobile.ui.shell.MainShell
import app.antresol.ui.theme.AntresolTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The phone side of the 2026-11-15 owner list against [FakeServer]: Continue Watching episode
 * stills, watched cards (eye + dim), episode stills on the series page, cast photos, the trailer
 * button and chooser. Screenshots go to `mobile/build/screenshots/owner-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneOwnerListUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolMobileApp>()
    private val shots = File("build/screenshots").apply { mkdirs() }

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
        val state = session.state.value as AuthState.SignedIn
        // Coil's loader is a process-wide singleton: one built for an earlier test's application
        // would send that application's (absent) token. Use this application's loader, without
        // the crossfade: Robolectric draws no animation frames, so a first (not memory-cached) load
        // would stay at the crossfade's start, an empty frame, in the screenshots.
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).build())
        compose.setContent { AntresolTheme { MainShell(session, state) } }
        waitFor(hasText("Continue Watching"))
    }

    @After
    fun tearDown() = fake.shutdown()

    @Test
    fun homeSeriesAndMovieDetail() {
        settle()
        shot("owner-phone-home")
        list().performScrollToNode(hasText("New Movies"))
        settle()
        shot("owner-phone-home-watched")
        assertTrue("Continue Watching episode still", fake.requests.any { it.contains("/api/images/frame/${FakeServer.EPISODE_IDS[1]}") })

        // The series page: trailer icon beside Play, episode stills, cast photos.
        list().performScrollToNode(hasText("New Series"))
        compose.onAllNodes(hasText("The Test Show"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("detail-trailer"))
        settle()
        shot("owner-phone-series")
        list().performScrollToNode(hasTestTag("episode-play-${FakeServer.EPISODE_IDS[2]}"))
        settle()
        shot("owner-phone-series-episodes")
        val frames = fake.requests.filter { it.contains("/api/images/frame/") }.toSet()
        assertTrue("episode stills: $frames", frames.size >= 3)
        list().performScrollToNode(hasText("Cast"))
        settle()
        shot("owner-phone-series-cast")
        assertEquals("Bearer test-device-token", fake.imageAuth["/api/images/cast/Ada%20Lovelace"])

        // One trailer: straight to the YouTube app.
        list().performScrollToNode(hasTestTag("detail-trailer"))
        compose.onNode(hasTestTag("detail-trailer")).performClick()
        assertEquals("vnd.youtube:seriesTrailer", shadowOf(compose.activity).nextStartedActivity?.data?.toString())
    }

    @Test
    fun movieWithTwoTrailersOpensTheChooser() {
        // Movie 1 in New Movies (in Continue Watching a tap would resume it).
        list().performScrollToNode(hasText("New Movies"))
        compose.onAllNodes(hasText("Movie 1"), useUnmergedTree = true).onLast().performClick()
        waitFor(hasTestTag("detail-trailer"))
        settle()
        shot("owner-phone-movie")
        compose.onNode(hasTestTag("detail-trailer")).performClick()
        waitFor(hasText("Teaser"))
        settle()
        shot("owner-phone-trailer-sheet")
        compose.onNode(hasTestTag("trailer-0")).performClick()
        assertEquals("vnd.youtube:movieTrailer1", shadowOf(compose.activity).nextStartedActivity?.data?.toString())
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
            shot("owner-phone-failure")
            throw e
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

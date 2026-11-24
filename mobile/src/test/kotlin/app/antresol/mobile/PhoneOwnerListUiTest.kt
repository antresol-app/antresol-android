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
import app.antresol.mobile.player.TrailerPlayerActivity
import app.antresol.ui.trailers.TrailerPhase
import org.robolectric.Robolectric
import app.antresol.mobile.ui.components.LocalPosterZoom
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
 * button and chooser, and the trailer player (own and embedded). Screenshots go to `mobile/build/screenshots/owner-*.png`.
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
        // Screenshots draw the view on a software canvas, which the poster zoom's hardware layers cannot be drawn on.
        compose.setContent { CompositionLocalProvider(LocalPosterZoom provides false) { AntresolTheme { MainShell(session, state) } } }
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
        // At a width step of the server, not the original.
        assertTrue(fake.requests.any { it.contains("/api/images/cast/Ada%20Lovelace?w=") })

        // One trailer, which the server plays: straight to the trailer player.
        list().performScrollToNode(hasTestTag("detail-trailer"))
        compose.onNode(hasTestTag("detail-trailer")).performClick()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(TrailerPlayerActivity::class.java.name, started?.component?.className)
        assertEquals("/api/trailers/seriesTrailer/play", started?.getStringExtra("play"))
        assertEquals("Season 1 Trailer", started?.getStringExtra("name"))
    }

    @Test
    fun movieWithTwoTrailersOpensTheChooser() {
        // Movie 1 in New Movies (in Continue Watching a tap would resume it). The whole card is tapped, not its title:
        // with New Releases above, the row ends at the bottom edge and the title sits under the tab bar.
        list().performScrollToNode(hasText("Movie 2"))
        compose.onAllNodes(hasText("Movie 1")).onLast().performClick()
        waitFor(hasTestTag("detail-trailer"))
        settle()
        shot("owner-phone-movie")
        compose.onNode(hasTestTag("detail-trailer")).performClick()
        waitFor(hasText("Teaser"))
        settle()
        shot("owner-phone-trailer-sheet")
        compose.onNode(hasTestTag("trailer-0")).performClick()
        val first = shadowOf(compose.activity).nextStartedActivity
        assertEquals(TrailerPlayerActivity::class.java.name, first?.component?.className)
        assertEquals("/api/trailers/movieTrailer1/play", first?.getStringExtra("play"))
        // The teaser has no `play`: the same activity, with the embedded player for its key.
        compose.onNode(hasTestTag("detail-trailer")).performClick()
        waitFor(hasTestTag("trailer-1"))
        compose.onNode(hasTestTag("trailer-1")).performClick()
        val teaser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(TrailerPlayerActivity::class.java.name, teaser?.component?.className)
        assertEquals("", teaser?.getStringExtra("play"))
        assertEquals("movieTeaser2", teaser?.getStringExtra("key"))
    }

    /**
     * The trailer player, landscape: the name and a spinner while the server is busy (503 +
     * Retry-After, asked again), then the controls over the picture. Back closes it; no progress.
     */
    @Test
    @Config(qualifiers = "en-w800dp-h360dp-land-xxhdpi")
    fun trailerPlaysInTheAppsOwnPlayer() {
        fake.trailerPlay = FakeServer.TrailerPlay.BusyOnce
        val activity = trailerActivity("/api/trailers/movieTrailer1/play", "movieTrailer1", "Official Trailer")
        val model = requireNotNull(activity.viewModel)
        assertEquals(TrailerPhase.Loading, model.phase)
        waitUntil { fake.requests.contains("POST /api/trailers/movieTrailer1/play") }
        shot(activity, "owner-phone-trailer-loading")
        waitUntil(20_000) { model.phase == TrailerPhase.Playing }
        settle()
        shot(activity, "owner-phone-trailer-playing")
        assertEquals(2, fake.requests.count { it == "POST /api/trailers/movieTrailer1/play" })
        assertTrue(fake.requests.any { it.startsWith("GET /api/trailers/movieTrailer1.mp4?mk=test-mk") })
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        assertTrue(fake.requests.none { it.startsWith("PUT /api/progress") || it.startsWith("POST /api/play") })
    }

    /** The server could not fetch the trailer (502): its message with «Повторить»; retrying plays it. */
    @Test
    @Config(qualifiers = "en-w800dp-h360dp-land-xxhdpi")
    fun failedTrailerShowsTheErrorWithRetry() {
        fake.trailerPlay = FakeServer.TrailerPlay.Failed
        val activity = trailerActivity("/api/trailers/seriesTrailer/play", "seriesTrailer", "Season 1 Trailer")
        val model = requireNotNull(activity.viewModel)
        waitUntil { model.phase is TrailerPhase.Failed }
        settle()
        shot(activity, "owner-phone-trailer-error")
        assertEquals("Could not fetch the trailer.", (model.phase as TrailerPhase.Failed).error.message)
        fake.trailerPlay = FakeServer.TrailerPlay.Ready
        model.retry()
        waitUntil(20_000) { model.phase == TrailerPhase.Playing }
    }

    /** No `play`: the embedded official player, loaded under an https base URL (a referrer) with autoplay. */
    @Test
    @Config(qualifiers = "en-w800dp-h360dp-land-xxhdpi")
    fun trailerWithoutPlayUsesTheEmbeddedPlayer() {
        val activity = trailerActivity("", "movieTeaser2", "Teaser")
        assertEquals(null, activity.viewModel)
        settle()
        shot(activity, "owner-phone-trailer-embed-loading")
        val web = findWebView(activity.window.decorView) ?: error("no WebView")
        val page = shadowOf(web).lastLoadDataWithBaseURL ?: error("nothing loaded")
        // The app's identity, as the site asks of a WebView: never the site's own address.
        assertEquals("https://app.antresol.mobile/", page.baseUrl)
        assertTrue(page.data.contains("videoId: 'movieTeaser2'"))
        assertTrue(page.data.contains("host: 'https://www.youtube-nocookie.com'"))
        assertTrue(page.data.contains("origin: 'https://app.antresol.mobile'"))
        assertTrue(web.settings.javaScriptEnabled)
        assertEquals(false, web.settings.mediaPlaybackRequiresUserGesture)
        // A link tapped in the player does not replace the player page; the player's own frame loads.
        val client = shadowOf(web).webViewClient
        assertTrue(client.shouldOverrideUrlLoading(web, navigation("https://www.youtube.com/watch?v=movieTeaser2", mainFrame = true)))
        assertEquals(false, client.shouldOverrideUrlLoading(web, navigation("https://www.youtube-nocookie.com/embed/movieTeaser2", mainFrame = false)))
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    private fun navigation(url: String, mainFrame: Boolean) = object : android.webkit.WebResourceRequest {
        override fun getUrl(): android.net.Uri = android.net.Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    private fun trailerActivity(play: String, key: String, name: String): TrailerPlayerActivity {
        val intent = TrailerPlayerActivity.intent(app, play, key, name)
        return Robolectric.buildActivity(TrailerPlayerActivity::class.java, intent).setup().visible().get()
    }

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
    }

    private fun findWebView(view: android.view.View): android.webkit.WebView? = when (view) {
        is android.webkit.WebView -> view
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun shot(activity: android.app.Activity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

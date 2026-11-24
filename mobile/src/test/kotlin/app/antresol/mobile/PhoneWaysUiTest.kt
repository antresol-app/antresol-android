package app.antresol.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.AddonWay
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.core.model.RunRequest
import app.antresol.core.model.WayPlay
import app.antresol.core.model.WayProgress
import app.antresol.core.model.WayQuality
import app.antresol.core.model.WaySubtitle
import app.antresol.core.ways.PlaybackDisplay
import app.antresol.core.ways.WayCall
import app.antresol.core.ways.WayTarget
import app.antresol.core.ways.WayTicket
import app.antresol.core.ways.WayPlayback
import app.antresol.fixtures.FakeServer
import app.antresol.mobile.player.VideoPlayerActivity
import app.antresol.mobile.ui.components.LocalPosterZoom
import app.antresol.mobile.ui.shell.MainShell
import app.antresol.ui.playback.VideoPlayerViewModel
import app.antresol.ui.theme.AntresolTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The addons' ways on the phone against [FakeServer] speaking the `x/app` protocol with one addon
 * ("alpha"): the ways on a library movie, the choice step by step, a way that plays at once, the
 * search section of the addon and the page of a title off the library with its keep way and ring.
 * Screenshots go to `mobile/build/screenshots/ways-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneWaysUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolMobileApp>()
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
        val state = session.state.value as AuthState.SignedIn
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).build())
        // Screenshots draw the view on a software canvas, which the poster zoom's hardware layers cannot be drawn on.
        compose.setContent { CompositionLocalProvider(LocalPosterZoom provides false) { AntresolTheme { MainShell(session, state) } } }
        waitFor(hasText("Continue Watching"))
    }

    @After
    fun tearDown() = fake.shutdown()

    private fun openMovie() {
        list().performScrollToNode(hasText("Movie 2"))
        compose.onAllNodes(hasText("Movie 1")).onLast().performClick()
        waitFor(hasTestTag("detail-play"))
    }

    @Test
    fun aLibraryMovieChoosesItsWayStepByStepAndPlaysThroughTheInputPlayer() {
        start()
        openMovie()
        waitFor(hasTestTag("ways-more"))
        settle()
        shot("ways-phone-movie")
        // The keep way is for what is not in the library: not here.
        assertTrue(compose.onAllNodes(hasTestTag("way-keep-keep")).fetchSemanticsNodes().isEmpty())

        compose.onNode(hasTestTag("ways-more")).performClick()
        waitFor(hasTestTag("way-watch"))
        shot("ways-phone-sheet")
        compose.onNode(hasTestTag("way-watch")).performClick()

        // No remembered choice: the addon answered `pick`, and the first step is asked for.
        waitFor(hasTestTag("way-step-sheet"))
        waitFor(hasText("Source One"))
        shot("ways-phone-step-source")
        assertTrue(compose.onAllNodes(hasText("Pick a source"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(fake.addonCalls.any { it.startsWith("POST app/run") && it.contains("\"quick\":true") })

        compose.onNode(hasTestTag("way-step-row-0")).performClick()
        waitFor(hasText("1080p"))
        shot("ways-phone-step-quality")
        // Back to the step before, and on again.
        compose.onNode(hasTestTag("way-step-back")).performClick()
        waitFor(hasText("Source Two"))
        compose.onNode(hasTestTag("way-step-row-1")).performClick()
        waitFor(hasText("720p"))
        compose.onNode(hasTestTag("way-step-row-0")).performClick()

        // The final row ran the way with its ref; the answer was an input: the player starts with it.
        val started = waitStarted()
        assertEquals(VideoPlayerActivity::class.java.name, started.component?.className)
        val playback = WayTicket.get(started.getStringExtra("way").orEmpty()) as WayPlayback.Input
        assertEquals("in-1", playback.id)
        assertEquals("Movie 1", playback.display.title)
        // The first step has no ref; each next one the ref of the row picked (going back asks nothing again).
        val steps = fake.addonCalls.filter { it.startsWith("POST app/step") }
        assertEquals(3, steps.size)
        assertTrue(steps[0], !steps[0].contains("\"ref\""))
        assertTrue(steps[1], steps[1].contains("\"ref\":\"src-1\""))
        assertTrue(steps[2], steps[2].contains("\"ref\":\"src-2\""))
        val run = fake.addonCalls.last { it.startsWith("POST app/run") }
        assertTrue(run, run.contains("\"ref\":\"q-1080\"") || run.contains("\"ref\":\"q-720\""))
        assertTrue(run, run.contains("\"tmdbId\":${FakeServer.MOVIE_TMDB}"))
    }

    @Test
    fun aRememberedChoicePlaysAtOnceWithoutTheSheet() {
        fake.wayRemembered = true
        start()
        openMovie()
        waitFor(hasTestTag("ways-more"))
        compose.onNode(hasTestTag("ways-more")).performClick()
        waitFor(hasTestTag("way-watch"))
        compose.onNode(hasTestTag("way-watch")).performClick()
        val started = waitStarted()
        assertEquals(VideoPlayerActivity::class.java.name, started.component?.className)
        assertTrue("no step was asked for", fake.addonCalls.none { it.startsWith("POST app/step") })
    }

    @Test
    fun searchAddsTheAddonSectionAndATitleOffTheLibraryOpensItsPage() {
        fake.keepActive = true
        start()
        compose.onNode(hasTestTag("tab-Search")).performClick()
        waitFor(hasTestTag("search-field"))
        compose.onNode(hasTestTag("search-field")).performTextInput("far")
        // The library's results first; the addon's section, titled by the addon's own words, after them.
        waitFor(hasText("Find and watch"))
        waitFor(titled("Far Away Film"))
        settle()
        shot("ways-phone-search")
        assertTrue(fake.addonCalls.any { it.startsWith("GET app/search?q=far") })

        compose.onAllNodes(titled("Far Away Film"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("way-primary"))
        waitFor(hasTestTag("way-keep-keep"))
        settle()
        shot("ways-phone-title")
        assertTrue(fake.requests.contains("GET /api/title/movie/${FakeServer.OUTSIDE_TMDB}"))
        // Nothing of the library plays here: the first way is the main button, and the page has no watchlist or watched state.
        assertTrue(compose.onAllNodes(hasTestTag("detail-play")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("toggle-watchlist")).fetchSemanticsNodes().isEmpty())
        // The keep way is working: its words, and «Cancel» behind its button.
        waitFor(hasText("Saved 40%"))
        assertTrue(fake.addonCalls.any { it.startsWith("GET app/state?kind=movie&tmdbId=${FakeServer.OUTSIDE_TMDB}") })
        compose.onNode(hasTestTag("way-keep-keep")).performClick()
        waitFor(hasTestTag("way-keep-menu"))
        shot("ways-phone-keep-menu")
        compose.onNode(hasTestTag("way-keep-cancel")).performClick()
        waitUntil { fake.addonCalls.any { it.startsWith("POST app/cancel") } }
        waitFor(hasText("Cancelled"))
    }

    /** Like the web's «Скачать»: a tap on a keep way opens the choice (quality, release) and never runs by itself. */
    @Test
    fun aKeepWayThatIsNotWorkingOpensTheChoiceAndRunsFromAFinalRow() {
        start()
        compose.onNode(hasTestTag("tab-Search")).performClick()
        waitFor(hasTestTag("search-field"))
        compose.onNode(hasTestTag("search-field")).performTextInput("far")
        waitFor(titled("Far Away Film"))
        settle()
        compose.onAllNodes(titled("Far Away Film"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("way-primary"))
        waitFor(hasTestTag("way-keep-keep"))
        compose.onNode(hasTestTag("way-keep-keep")).performClick()
        waitFor(hasTestTag("way-step-sheet"))
        waitFor(hasText("Source One"))
        shot("ways-phone-keep-step")
        assertTrue("the tap asked for the first step, not for a run", fake.addonCalls.none { it.startsWith("POST app/run") })
        assertTrue(fake.addonCalls.last { it.startsWith("POST app/step") }.contains("\"way\":\"keep\""))
        compose.onNode(hasTestTag("way-step-row-0")).performClick()
        waitFor(hasText("1080p"))
        compose.onNode(hasTestTag("way-step-row-0")).performClick()
        waitFor(hasText("Started"))
        val run = fake.addonCalls.last { it.startsWith("POST app/run") }
        assertTrue(run, run.contains("\"way\":\"keep\"") && run.contains("\"ref\":\"q-1080\"") && !run.contains("quick"))
    }

    @Test
    fun withoutAddonsNothingNewShowsAndTheProtocolIsNotAsked() {
        start(addons = false)
        openMovie()
        settle()
        assertTrue(compose.onAllNodes(hasTestTag("ways-more")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("way-primary")).fetchSemanticsNodes().isEmpty())
        assertTrue(fake.requests.none { it.contains("/x/app") })
        compose.onNode(hasTestTag("tab-Search")).performClick()
        waitFor(hasTestTag("search-field"))
        compose.onNode(hasTestTag("search-field")).performTextInput("movie")
        waitFor(hasText("Movies & Shows"))
        assertFalse(fake.requests.any { it.contains("/x/app") })
    }

    /** The player of a way's input: the preparing (202) is waited out, then the video plays and its position goes to the input. */
    @Test
    fun theInputPlayerWaitsWhileTheVideoIsPreparedAndReportsItsPosition() {
        fake.addons = true
        fake.inputPendingAsks = 2
        fake.start()
        val session = app.session
        runBlocking {
            session.restore()
            if (session.state.value !is AuthState.NoServer) session.changeServer()
            session.connect(fake.baseUrl)
            session.signIn(Profile(1, "Alex", "grad-3"), null)
        }
        val token = WayTicket.put(WayPlayback.Input("in-1", PlaybackDisplay("Movie 1", kind = "movie")))
        val intent = android.content.Intent(app, VideoPlayerActivity::class.java).putExtra("way", token)
        val controller = Robolectric.buildActivity(VideoPlayerActivity::class.java, intent).setup().visible()
        val activity = controller.get()
        waitUntil(30_000) { fake.requests.count { it == "POST /api/play/input/in-1" } >= 3 }
        assertTrue(fake.requests.contains("GET /api/play/input/in-1"))
        waitUntil(30_000) { fake.requests.any { it.startsWith("GET /api/play/input/in-1/direct?mk=test-mk") } }
        activity.finish()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        waitUntil { fake.requests.contains("PUT /api/play/input/in-1/progress") }
        assertNotNull(fake.requests.firstOrNull { it == "PUT /api/play/input/in-1/progress" })
    }

    /** The next activity started that is the player (the rule's own launch of the test activity does not count). */
    /**
     * A stream the addon serves itself: its link and subtitle get the media key, the position goes to the
     * addon under its session and the session is closed with the player. The first link is stale (410):
     * the way runs again with `refresh` and plays from where it was.
     */
    @Test
    fun aStreamTheAddonServesPlaysWithTheMediaKeyReportsAndRefreshesAStaleLink() {
        fake.addons = true
        fake.staleLink = true
        fake.start()
        val session = app.session
        runBlocking {
            session.restore()
            if (session.state.value !is AuthState.NoServer) session.changeServer()
            session.connect(fake.baseUrl)
            session.signIn(Profile(1, "Alex", "grad-3"), null)
        }
        val play = WayPlay(
            url = "/api/addons/alpha/x/media/s1/video.mp4",
            mode = "stream",
            kind = "mp4",
            durationMs = 30_000,
            subtitles = listOf(WaySubtitle(index = 0, lang = "en", label = "English", codec = "webvtt", url = "/api/addons/alpha/x/media/s1/subs/0.vtt")),
            session = "s1",
            progress = WayProgress(every = 3),
        )
        val request = RunRequest("watch", "movie", FakeServer.MOVIE_TMDB, quick = true)
        val token = WayTicket.put(WayPlayback.Direct("alpha", request, play, PlaybackDisplay("Movie 1", kind = "movie")))
        val intent = android.content.Intent(app, VideoPlayerActivity::class.java).putExtra("way", token)
        val controller = Robolectric.buildActivity(VideoPlayerActivity::class.java, intent).setup().visible()
        val activity = controller.get()
        // The stale link: a run with `refresh` made the second session, which plays.
        waitUntil(30_000) { fake.addonCalls.any { it.startsWith("GET media/s2/video.mp4") } }
        val refresh = fake.addonCalls.first { it.startsWith("POST app/run") }
        assertTrue(refresh, refresh.contains("\"refresh\":true") && refresh.contains("\"way\":\"watch\"") && refresh.contains("\"startSec\""))
        assertTrue("the stream carries the media key", fake.addonCalls.first { it.startsWith("GET media/s1/video.mp4") }.contains("mk=test-mk"))
        // The subtitle the player offers is the new session's, and its link carries the key too.
        val player = ViewModelProvider(activity)[VideoPlayerViewModel::class.java]
        waitUntil { player.subtitleTracks.any { it.url.orEmpty().contains("/media/s2/") } }
        val track = player.subtitleTracks.single()
        assertTrue(track.url.orEmpty(), track.url.orEmpty().contains("mk=test-mk"))
        assertFalse("no quality choice for a stream the addon serves", player.canChooseQuality)
        player.selectSubtitle(track)
        waitUntil { fake.addonCalls.any { it.startsWith("GET media/s2/subs/0.vtt?mk=test-mk") } }
        // The player's own routes are not used for it: no input, no core session.
        assertTrue(fake.requests.none { it.contains("/api/play/input") || it.startsWith("POST /api/play/") })
        // Closing the player: its last position, then the addon's session.
        activity.finish()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        waitUntil { fake.addonCalls.any { it.startsWith("PUT app/progress") && it.contains("\"session\":\"s2\"") } }
        val put = fake.addonCalls.first { it.startsWith("PUT app/progress") && it.contains("\"session\":\"s2\"") }
        assertTrue(put, put.contains("\"way\":\"watch\"") && put.contains("\"tmdbId\":${FakeServer.MOVIE_TMDB}"))
        waitUntil { fake.addonCalls.any { it.startsWith("DELETE app/play/s2") } }
        // The stale session is closed too, once.
        waitUntil { fake.addonCalls.any { it.startsWith("DELETE app/play/s1") } }
    }

    private fun signIn() {
        val session = app.session
        runBlocking {
            session.restore()
            if (session.state.value !is AuthState.NoServer) session.changeServer()
            session.connect(fake.baseUrl)
            session.signIn(Profile(1, "Alex", "grad-3"), null)
        }
    }

    private fun directPlayback(call: WayCall? = null): WayPlayback.Direct {
        val play = WayPlay(
            url = "/api/addons/alpha/x/media/s1/video.mp4",
            mode = "stream",
            kind = "mp4",
            durationMs = 30_000,
            subtitles = listOf(WaySubtitle(index = 0, lang = "en", label = "English", codec = "webvtt", url = "/api/addons/alpha/x/media/s1/subs/0.vtt")),
            quality = WayQuality(listOf("1080p", "720p"), "1080p"),
            session = "s1",
            progress = WayProgress(every = 3),
            ref = "again-ref",
        )
        val request = RunRequest("watch", "movie", FakeServer.MOVIE_TMDB, ref = "r-1")
        return WayPlayback.Direct("alpha", request, play, PlaybackDisplay("Movie 1", kind = "movie"), call)
    }

    /**
     * A stream the addon serves that lists qualities and the ref to play one: the player's quality menu offers the
     * addon's own names, and another one runs the way again with that ref and the quality, from where it is.
     */
    @Test
    fun aStreamWithQualitiesOffersThemAndSwitchingAsksTheAddonAgainFromWhereItIs() {
        fake.addons = true
        fake.wayQualities = true
        fake.start()
        signIn()
        val token = WayTicket.put(directPlayback())
        val intent = android.content.Intent(app, VideoPlayerActivity::class.java).putExtra("way", token)
        val controller = Robolectric.buildActivity(VideoPlayerActivity::class.java, intent).setup().visible()
        val player = ViewModelProvider(controller.get())[VideoPlayerViewModel::class.java]
        waitUntil(30_000) { player.source != null }
        assertTrue(player.canChooseQuality)
        assertEquals(listOf("1080p", "720p"), player.wayQualities)
        assertEquals("1080p", player.quality)
        // No choice of the source: this playback was made without the run that made it.
        assertFalse(player.canChooseSource)
        player.selectQuality("720p")
        waitUntil(30_000) { fake.addonCalls.any { it.startsWith("POST app/run") && it.contains("\"quality\":\"720p\"") } }
        val run = fake.addonCalls.first { it.startsWith("POST app/run") }
        assertTrue(run, run.contains("\"ref\":\"again-ref\"") && run.contains("\"refresh\":true") && run.contains("\"startSec\""))
        // The ref the answer gave plays it again; what the viewer began with (`quick` and the first ref) is not sent.
        assertFalse(run, run.contains("\"r-1\"") || run.contains("quick"))
        waitUntil(30_000) { player.quality == "720p" }
        assertEquals(listOf("1080p", "720p"), player.wayQualities)
        controller.get().finish()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * «Audio & source…» of a way's stream: the way's own choice, step by step, from the player; the row it ends on
     * replaces the stream, asked to start where the video is.
     */
    @Test
    fun theSourceChoiceOfAStreamReplacesItWhereItIs() {
        fake.addons = true
        fake.wayDirect = true
        fake.start()
        signIn()
        val way = AddonWay(
            id = "watch", label = "Watch from a source", icon = "globe", kinds = listOf("movie", "series"), addonId = "alpha",
        )
        val call = WayCall(way, WayTarget("movie", FakeServer.MOVIE_TMDB, inLibrary = true, title = "Movie 1"))
        val first = directPlayback(call)
        val token = WayTicket.put(first)
        val intent = android.content.Intent(app, VideoPlayerActivity::class.java).putExtra("way", token)
        val controller = Robolectric.buildActivity(VideoPlayerActivity::class.java, intent).setup().visible()
        val player = ViewModelProvider(controller.get())[VideoPlayerViewModel::class.java]
        waitUntil(30_000) { player.source != null }
        assertTrue(player.canChooseSource)
        assertTrue("the audio menu has the row", player.hasExtraAudioMenu)
        player.openSourceChoice()
        val ways = player.ways!!
        waitUntil { ways.picker.value?.step?.title == "Source" }
        ways.choose(ways.picker.value!!.step!!.rows.first())
        waitUntil { ways.picker.value?.step?.title == "Quality" }
        ways.choose(ways.picker.value!!.step!!.rows.first())
        // The row's run starts where the player is, and what it gave plays in its place.
        waitUntil(30_000) { fake.addonCalls.any { it.startsWith("POST app/run") && it.contains("\"ref\":\"q-1080\"") } }
        val run = fake.addonCalls.first { it.startsWith("POST app/run") && it.contains("\"ref\":\"q-1080\"") }
        assertTrue(run, run.contains("\"startSec\""))
        waitUntil(30_000) { player.currentWay !== first && player.source != null }
        assertNull(ways.picker.value)
        controller.get().finish()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitStarted(): android.content.Intent {
        var found: android.content.Intent? = null
        waitUntil {
            while (true) {
                val next = shadowOf(compose.activity).nextStartedActivity ?: break
                if (next.component?.className == VideoPlayerActivity::class.java.name) {
                    found = next
                    break
                }
            }
            found != null
        }
        return found!!
    }

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out; the addon got ${fake.addonCalls}; requests ${fake.requests.takeLast(12)}" }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
    }

    /** A card's title: its text, or its description when the card draws only the picture. */
    private fun titled(title: String) = hasText(title) or hasContentDescription(title)

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
            shot("ways-phone-failure")
            throw IllegalStateException("${e.message}; the addon got ${fake.addonCalls}", e)
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

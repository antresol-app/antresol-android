package app.antresol.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.fixtures.FakeServer
import app.antresol.tv.ui.components.TopNavHeight
import app.antresol.tv.ui.shell.TAB_RESET_MS
import app.antresol.tv.ui.shell.MainShell
import app.antresol.ui.trailers.TrailerEmbed
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
 * The signed-in shell against [FakeServer] with D-pad key events: detail pages entered and left
 * many times, tabs, grids, cast, trailers, episode thumbs and watched marks. Screenshots go to
 * `tv/build/screenshots/shell-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvShellUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()
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
        // Coil's loader is a process-wide singleton: one built for an earlier test's application
        // would send that application's (absent) token. Use this application's loader, without
        // the crossfade: Robolectric draws no animation frames, so a first (not memory-cached) load
        // would stay at the crossfade's start, an empty frame, in the screenshots.
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).build())
        // The session's state as the app's root follows it: a switched device history reaches the shell.
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

    /**
     * Item 6: entering and leaving the same series again and again, Back pressed at every moment of
     * the page's load and transition. No detail page may be left behind, and the TV must never end
     * up frozen: nothing focused and the D-pad unable to bring focus back. On the code before the
     * fix (e9a3d7a) five of the 21 rounds froze: a detail page that loaded while leaving took focus
     * and dropped it as it was disposed.
     */
    @Test
    fun enteringAndLeavingTheSameSeriesNeverLosesFocus() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT) // hero Play → Details
        assertFocusedText("Details")
        compose.mainClock.autoAdvance = false
        val problems = ArrayList<String>()
        val unhighlighted = ArrayList<String>()
        val elsewhere = ArrayList<String>()
        for (delay in listOf(0L, 120L, 400L)) {
            fake.itemDelayMs = delay
            for (backAfterMs in listOf(16L, 48L, 96L, 160L, 240L, 400L, 700L)) {
                press(KeyEvent.KEYCODE_DPAD_CENTER)
                runFor(backAfterMs)
                press(KeyEvent.KEYCODE_BACK)
                runFor(2_400)
                val where = "item delay $delay ms, Back after $backAfterMs ms"
                val detail = compose.onAllNodes(hasTestTag("page/detail"), useUnmergedTree = true).fetchSemanticsNodes().size
                val focused = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
                val details = compose.onAllNodes(isFocused() and hasText("Details"), useUnmergedTree = true).fetchSemanticsNodes().size +
                    compose.onAllNodes(isFocused() and androidx.compose.ui.test.hasAnyDescendant(hasText("Details")), useUnmergedTree = true).fetchSemanticsNodes().size
                if (detail != 0) problems += "$where: the detail page is still there"
                if (focused.isEmpty()) {
                    shot("shell-hang-$delay-$backAfterMs")
                    // No element highlighted: the next D-pad key must bring focus back, else the TV is frozen.
                    press(KeyEvent.KEYCODE_DPAD_UP)
                    runFor(300)
                    if (compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
                        problems += "$where: nothing focused, and a D-pad key did not bring focus back (frozen)"
                    } else {
                        unhighlighted += where
                    }
                } else if (details == 0) {
                    elsewhere += where
                }
                // Start the next round from Details, whatever happened.
                compose.onNode(hasText("Details") and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                runFor(200)
            }
        }
        println("Rounds that needed one key to show focus again: $unhighlighted")
        println("Rounds that came back with focus on another Home element: $elsewhere")
        compose.mainClock.autoAdvance = true
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * Item 10: holding OK on a Continue Watching episode opens its page. The key's repeats and its
     * release land on the page that just opened and must not press its Play button.
     */
    @Test
    fun longPressOnContinueOpensTheDetailPageWithoutPlaying() {
        key(KeyEvent.KEYCODE_DPAD_DOWN) // hero → Continue Watching, first card (an episode)
        shot("shell-home-continue")
        hold(KeyEvent.KEYCODE_DPAD_CENTER, repeats = 30)
        waitFor(hasTestTag("page/detail"))
        waitFor(isFocused() and (hasText("Play") or hasAnyDescendant(hasText("Play"))))
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue("the held OK started playback: ${fake.requests}", fake.requests.none { it.startsWith("POST /api/play") })
        assertTrue(compose.onAllNodes(hasTestTag("page/detail"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // A plain click on the same card still resumes.
        key(KeyEvent.KEYCODE_BACK)
        waitFor(isFocused() and hasAnyDescendant(hasContentDescription("Episode 2") or hasText("Episode 2")))
    }

    /** Item 5: a tab left for less than 5 s keeps its place; after 5 s it is back at its root. */
    @Test
    fun tabsResetFiveSecondsAfterLeavingThem() {
        key(KeyEvent.KEYCODE_DPAD_UP) // Play → nav (Home pill)
        key(KeyEvent.KEYCODE_DPAD_RIGHT) // Movies
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Movie 1"))
        key(KeyEvent.KEYCODE_DPAD_DOWN) // into the grid: Movie 1
        key(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val deep = focusedTitle()
        check(deep != "Movie 1") { "did not move into the grid" }
        shot("shell-movies-scrolled")
        // Away and back within 5 s: the same place (the last element focused before the nav).
        val left = toNav()
        val leftAt = compose.mainClock.currentTime
        key(KeyEvent.KEYCODE_DPAD_RIGHT) // Shows
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_LEFT) // Movies
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        check(compose.mainClock.currentTime - leftAt < TAB_RESET_MS) { "took too long to come back" }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(left, focusedTitle())
        // Away for more than 5 s: Movies starts over on its first card.
        toNav()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Show 2"))
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Movie 1"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor(isFocused() and hasAnyDescendant(hasContentDescription("Movie 1") or hasText("Movie 1")))
    }

    /** Items 2, 3, 11: grids keep equal gutters, stay below the nav while scrolled, watched cards are dimmed with the eye. */
    @Test
    fun gridsSitBelowTheNavWithEqualGutters() {
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Movie 1"))
        settleImages()
        shot("shell-movies-grid")
        val cards = compose.onAllNodes(hasClickAction() and hasContentDescription("Movie", substring = true))
            .fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("no movie cards found", cards.isNotEmpty())
        val width = compose.activity.window.decorView.width.toFloat()
        val left = cards.minOf { it.left }
        val right = width - cards.maxOf { it.right }
        assertEquals("left gutter $left vs right gutter $right", left, right, 2f)

        toNav()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Show 2"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN, times = 3)
        settleImages()
        shot("shell-shows-scrolled")
        // Nothing of the grid is drawn under the nav: every visible card starts below it.
        val navBottom = with(compose.density) { TopNavHeight.toPx() }
        val shows = compose.onAllNodes(hasClickAction() and hasContentDescription("Show", substring = true))
            .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.bottom > 0 && it.top < compose.activity.window.decorView.height }
        assertTrue(shows.isNotEmpty())
        // Clipped to the area under the nav (bounds of a partly hidden card start at the clip).
        assertTrue("a card reaches under the nav: ${shows.minOf { it.top }} < $navBottom", shows.minOf { it.top } >= navBottom - 1f)
        // The nav stays up.
        compose.onNode(hasText("Movies") and hasClickAction()).assertExists()
    }

    /** Items 1, 4, 9, 11: the series page: trailer, distinct episode stills, watched episode dimmed, cast photos. */
    @Test
    fun seriesPageShowsTrailerEpisodeStillsAndCast() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER) // Details
        waitFor(hasTestTag("action-trailer"))
        settleImages()
        shot("shell-series-detail")
        key(KeyEvent.KEYCODE_DPAD_DOWN) // seasons pills
        key(KeyEvent.KEYCODE_DPAD_DOWN) // episodes
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        settleImages()
        shot("shell-series-episodes")
        val frames = fake.requests.filter { it.contains("/api/images/frame/") }.map { it.substringAfterLast('/') }.toSet()
        assertTrue("episode stills requested: $frames", frames.size >= 3)
        key(KeyEvent.KEYCODE_DPAD_DOWN) // cast
        repeat(3) { settleImages() }
        shot("shell-series-cast")
        // The TV loader asks for the absolute cast photo URL with the profile's Bearer token.
        assertEquals("Bearer test-device-token", fake.imageAuth["/api/images/cast/Ada%20Lovelace"])
        // At a width step of the server, not the original.
        assertTrue(fake.requests.any { it.contains("/api/images/cast/Ada%20Lovelace?w=") })
        compose.onAllNodes(hasText("Grace Hopper"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
    }

    /**
     * The frame endpoint can answer 503 (Retry-After) while the server is busy: episode tiles then
     * show the backdrop instead of staying blank, and the next visit asks for the frame again.
     */
    @Test
    fun busyFramesFallBackToTheBackdropAndAreRetried() {
        fake.framesBusy = true
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER) // Details
        waitFor(hasTestTag("action-trailer"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(2) { settleImages() }
        shot("shell-series-frames-busy")
        val frame = "/api/images/frame/${FakeServer.EPISODE_IDS[2]}"
        val busyAsks = fake.requests.count { it.endsWith(frame) }
        assertTrue("frame asked while busy", busyAsks >= 1)
        // The fallback was drawn: the series backdrop, not the empty placeholder.
        assertTrue(fake.requests.any { it.substringBefore('?').endsWith("/api/images/local/${FakeServer.SERIES_ID}/backdrop") })

        fake.framesBusy = false
        key(KeyEvent.KEYCODE_BACK)
        waitFor(isFocused() and (hasText("Details") or hasAnyDescendant(hasText("Details"))))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("action-trailer"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(2) { settleImages() }
        shot("shell-series-frames-retried")
        assertTrue("the failed frame is asked for again", fake.requests.count { it.endsWith(frame) } > busyAsks)
    }

    /**
     * A movie with two trailers opens the chooser. The first has `play`: it plays in the app's own
     * player, full screen without the nav, after the server was busy once (503 + Retry-After): the
     * name and a spinner first, then the player's controls. Back returns to the page. No progress
     * is written and no other app is started.
     */
    @Test
    fun movieTrailerPlaysInTheAppsOwnPlayer() {
        fake.trailerPlay = FakeServer.TrailerPlay.BusyOnce
        openMovieTrailerChooser()
        shot("shell-trailer-chooser")
        key(KeyEvent.KEYCODE_DPAD_CENTER) // the first: Official Trailer
        waitFor(hasTestTag("trailer-loading"))
        compose.onAllNodes(hasText("Official Trailer"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        shot("shell-trailer-loading")
        assertTrue("the nav is hidden", compose.onAllNodes(hasText("Movies") and hasClickAction()).fetchSemanticsNodes().isEmpty())
        waitFor(hasTestTag("trailer-playing"), timeoutMs = 20_000)
        settleImages()
        shot("shell-trailer-playing")
        assertEquals(2, fake.requests.count { it == "POST /api/trailers/movieTrailer1/play" })
        assertTrue("the MP4 was fetched", fake.requests.any { it.startsWith("GET /api/trailers/movieTrailer1.mp4?mk=test-mk") })
        assertNoOtherAppStarted()
        // A trailer has no quality menu and never writes progress.
        assertTrue(compose.onAllNodes(hasContentDescription("Quality"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasTestTag("action-trailer"))
        assertTrue(fake.requests.none { it.startsWith("PUT /api/progress") || it.startsWith("POST /api/play") })
    }

    /** The server could not fetch the trailer (502): its message and «Повторить», which plays it once the server can. */
    @Test
    fun failedTrailerShowsTheErrorWithRetry() {
        fake.trailerPlay = FakeServer.TrailerPlay.Failed
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER) // the series' Details
        waitFor(hasTestTag("action-trailer"))
        val onTrailer = isFocused() and (hasText("Trailer") or hasAnyDescendant(hasText("Trailer")))
        repeat(6) { if (compose.onAllNodes(onTrailer).fetchSemanticsNodes().isEmpty()) key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        waitFor(onTrailer)
        key(KeyEvent.KEYCODE_DPAD_CENTER) // one trailer: plays at once
        waitFor(hasTestTag("trailer-failed"))
        waitFor(isFocused() and (hasText("Try Again") or hasAnyDescendant(hasText("Try Again"))))
        shot("shell-trailer-error")
        compose.onAllNodes(hasText("Could not fetch the trailer."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty().let(::assertTrue)
        fake.trailerPlay = FakeServer.TrailerPlay.Ready
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("trailer-playing"), timeoutMs = 20_000)
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasTestTag("action-trailer"))
    }

    /**
     * The teaser has no `play` (a public or older server): the official embedded player in a
     * WebView, loaded under an https base URL so the embed gets a referrer, with autoplay and the
     * IFrame API; OK pauses through it; Back destroys the WebView. No other app is started.
     */
    @Test
    fun trailerWithoutPlayUsesTheEmbeddedPlayer() {
        openMovieTrailerChooser()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor(isFocused() and (hasText("Teaser") or hasAnyDescendant(hasText("Teaser"))))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("trailer-loading"))
        shot("shell-trailer-embed-loading")
        assertNoOtherAppStarted()
        val web = findWebView(compose.activity.window.decorView) ?: error("no WebView")
        val shadow = org.robolectric.Shadows.shadowOf(web)
        val page = shadow.lastLoadDataWithBaseURL ?: error("nothing loaded")
        val origin = "https://" + compose.activity.packageName
        assertEquals("$origin/", page.baseUrl)
        assertEquals("text/html", page.mimeType)
        for (part in listOf("videoId: 'movieTeaser2'", "host: 'https://www.youtube-nocookie.com'", "autoplay: 1", "playsinline: 1", "rel: 0", "origin: '$origin'", "https://www.youtube.com/iframe_api")) {
            assertTrue("page has $part", page.data.contains(part))
        }
        assertTrue(web.settings.javaScriptEnabled)
        assertEquals(false, web.settings.mediaPlaybackRequiresUserGesture)
        // The player reports «playing» through the bridge; OK then pauses it through the IFrame API.
        val bridge = shadow.getJavascriptInterface(TrailerEmbed.BRIDGE) ?: error("no bridge")
        bridge.javaClass.getMethod("onState", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(bridge, 1, 1)
        waitFor(hasTestTag("trailer-playing"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("antresolToggle()", shadow.lastEvaluatedJavascript)
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasTestTag("action-trailer"))
        assertTrue("the WebView is destroyed", shadow.wasDestroyCalled())
    }

    /** With the app in the background the embedded player is paused and its WebView told to stop working. */
    @Test
    fun embeddedTrailerIsPausedInTheBackground() {
        openMovieTrailerChooser()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor(isFocused() and (hasText("Teaser") or hasAnyDescendant(hasText("Teaser"))))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("trailer-loading"))
        val web = findWebView(compose.activity.window.decorView) ?: error("no WebView")
        val shadow = org.robolectric.Shadows.shadowOf(web)
        assertEquals(false, shadow.wasOnPauseCalled())
        val bridge = shadow.getJavascriptInterface(TrailerEmbed.BRIDGE) ?: error("no bridge")
        bridge.javaClass.getMethod("onState", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(bridge, 1, 1)
        waitFor(hasTestTag("trailer-playing"))
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertEquals(TrailerEmbed.PAUSE_JS, shadow.lastEvaluatedJavascript)
        assertTrue("the WebView is paused", shadow.wasOnPauseCalled())
    }

    /** Continue Watching → the movie (long press) → its Trailer button → the chooser, first row focused. */
    private fun openMovieTrailerChooser() {
        key(KeyEvent.KEYCODE_DPAD_DOWN) // Continue Watching
        key(KeyEvent.KEYCODE_DPAD_RIGHT) // the movie
        hold(KeyEvent.KEYCODE_DPAD_CENTER, repeats = 3) // long press → its page
        waitFor(hasTestTag("action-trailer"))
        settleImages()
        shot("shell-movie-detail")
        val onTrailer = isFocused() and (hasText("Trailer") or hasAnyDescendant(hasText("Trailer")))
        repeat(5) { if (compose.onAllNodes(onTrailer).fetchSemanticsNodes().isEmpty()) key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        waitFor(onTrailer)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(isFocused() and (hasText("Official Trailer") or hasAnyDescendant(hasText("Official Trailer"))))
    }

    /** No ACTION_VIEW intent (a trailer app or the browser); the rule's own activity launch does not count. */
    private fun assertNoOtherAppStarted() {
        val shadow = org.robolectric.Shadows.shadowOf(compose.activity)
        while (true) {
            val intent = shadow.nextStartedActivity ?: return
            assertTrue("started $intent", intent.action != android.content.Intent.ACTION_VIEW)
        }
    }

    private fun findWebView(view: android.view.View): android.webkit.WebView? = when (view) {
        is android.webkit.WebView -> view
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    /** Item 8: Settings keep the profile, playback and language; no server or devices sections. */
    @Test
    fun settingsHaveNoServerOrDevices() {
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 6) // past Music to the avatar
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Switch Profile"))
        shot("shell-settings")
        for (gone in listOf("SERVER", "Server Version", "Address", "Change Server", "SIGNED-IN DEVICES")) {
            assertTrue("$gone still shown", compose.onAllNodes(hasText(gone, ignoreCase = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
        assertTrue(fake.requests.none { it.contains("/api/me/devices") || it.contains("/api/library/status") })
    }

    /**
     * «Отдельная история на этом устройстве»: the row shows the state, the picker switches to an
     * existing history, the shell starts again on Home with that history's Continue Watching, and
     * switching back asks the web's merge question («Перенести в мой профиль» merges and drops it).
     */
    @Test
    fun separateHistoryIsPickedAndTurnedOffWithTheMergeQuestion() {
        openSettings()
        historyRow()
        assertFocusedText("Off")
        shot("shell-history-row")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Living Room"))
        waitFor(isFocused() and hasAnyDescendant(hasText("Off")))
        shot("shell-history-picker")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedText("Living Room")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        // The shell starts again on Home, with this device's own Continue Watching.
        waitFor((hasContentDescription("Movie 11") or hasText("Movie 11")))
        assertEquals(20L, fake.deviceHistory)
        assertTrue(fake.requests.contains("PUT /api/me/devices/current/history"))
        settleImages()
        shot("shell-history-home")

        openSettings()
        historyRow()
        assertFocusedText("Living Room")
        waitFor(hasText("History: “Living Room”"))
        shot("shell-history-on")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(isFocused() and hasAnyDescendant(hasText("Living Room")))
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertFocusedText("Off")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Return “Living Room” to your history?"))
        assertFocusedText("Cancel")
        shot("shell-history-off")
        key(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)
        assertFocusedText("Move to My Profile")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.onAllNodes((hasContentDescription("Movie 11") or hasText("Movie 11")), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        waitFor(hasText("Continue Watching"))
        assertEquals(null, fake.deviceHistory)
        assertTrue("merged history still listed", 20L !in fake.histories)
    }

    /** A new history by name (the TV keyboard's field), then this device writes into it. */
    @Test
    fun newSeparateHistoryIsCreatedByName() {
        openSettings()
        historyRow()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("New History…"))
        compose.onNode(hasText("New History…") and hasClickAction(), useUnmergedTree = false).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor(hasText("For example, Living Room"))
        waitFor(isFocused() and androidx.compose.ui.test.hasSetTextAction())
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Kitchen")
        compose.waitForIdle()
        shot("shell-history-new")
        compose.onNode(hasText("Create") and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor((hasContentDescription("Movie 11") or hasText("Movie 11")))
        assertEquals("Kitchen", fake.histories[fake.deviceHistory])
        assertTrue(fake.requests.contains("POST /api/me/managed"))
    }

    /** A kid's device history is an administrator's to change: no row. */
    @Test
    fun kidsHaveNoSeparateHistoryRow() {
        fake.kid = true
        runBlocking { app.session.refreshUser() }
        openSettings()
        waitFor(hasText("Playback", ignoreCase = true))
        shot("shell-history-kid")
        assertTrue(compose.onAllNodes(hasText("Separate History on This Device"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(fake.requests.none { it.contains("/api/me/managed") })
    }

    /** Item 7: no Books tab, no book scope or tiles in search. */
    @Test
    fun noBooksOnTv() {
        assertTrue(compose.onAllNodes(hasText("Books"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_LEFT, times = 2) // Search
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Browse"))
        shot("shell-search")
        for (gone in listOf("Books", "Audiobooks", "Manga & Comics")) {
            assertTrue("$gone still shown", compose.onAllNodes(hasText(gone), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
    }

    // region helpers

    private fun openSettings() {
        toNav()
        key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 6) // past Music to the avatar
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Switch Profile"))
        waitFor(isFocused() and hasAnyDescendant(hasText("Switch Profile")))
    }

    /** From Switch Profile to the history row: into the list, past quality, subtitles and autostart. */
    private fun historyRow() {
        waitFor(hasText("Separate History on This Device"))
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(6) {
            if (compose.onAllNodes(isFocused() and hasAnyDescendant(hasText("Separate History on This Device")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
            key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        assertFocusedText("Separate History on This Device")
    }

    /** Runs the clock and the main looper for [ms] in 16 ms frames, letting network answers arrive. */
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

    /** Holds [code]: the first down, [repeats] long-press repeats about 50 ms apart, then the release. */
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

    /** Up until a nav pill has focus; returns the title of the last element focused on the page. */
    private fun toNav(): String? {
        var last: String? = null
        repeat(8) {
            if (compose.onAllNodes(isFocused() and (hasText("Movies") or hasText("Shows") or hasText("Home"))).fetchSemanticsNodes().isNotEmpty()) return last
            last = runCatching { focusedTitle() }.getOrNull()
            key(KeyEvent.KEYCODE_DPAD_UP)
        }
        return last
    }

    /** Lets Coil fetch and draw the artwork. */
    private fun settleImages() {
        repeat(20) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            // Frames, so Coil's crossfade runs to its end before a screenshot.
            compose.mainClock.advanceTimeBy(50)
        }
        compose.waitForIdle()
    }

    /** The title of the focused card (artwork description or placeholder title). */
    private fun focusedTitle(): String {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        return node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }?.firstOrNull()
            ?: node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.firstOrNull()?.text
            ?: error("Focused node has no title: ${node.config}")
    }

    private fun key(code: Int, times: Int = 1) = repeat(times) {
        press(code)
        compose.waitForIdle()
        Thread.sleep(150)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
    }

    private fun assertFocusedText(text: String) {
        waitFor(isFocused() and (hasText(text) or androidx.compose.ui.test.hasAnyDescendant(hasText(text))))
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
            shot("shell-failure")
            throw e
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

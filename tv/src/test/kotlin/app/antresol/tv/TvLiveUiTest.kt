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
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Progress
import app.antresol.core.model.ProgressUpdate
import app.antresol.core.playback.Episodes
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.tv.playback.AudioContent
import app.antresol.tv.ui.root.AntresolTvRoot
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drives the real UI against a live server with D-pad key events and saves screenshots to
 * `tv/build/screenshots`. Runs only with `ANTRESOL_TEST_SERVER` set ("1" = http://127.0.0.1:8420);
 * signs in as profile 1 with `ANTRESOL_TEST_PASSWORD` (default `admin123`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvLiveUiTest {
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Playing writes progress, and the player's final write runs when the activity is destroyed,
     * after the test body. This outer rule waits for it, then puts back what the played items had.
     */
    private val restoreProgress = object : ExternalResource() {
        override fun after() {
            if (saved.isEmpty() && readerProgress.isEmpty()) return
            repeat(30) {
                ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
                Thread.sleep(100)
            }
            runBlocking {
                app.session.client?.let { client ->
                    saved.forEach { (id, before) -> client.restoreProgress(id, before) }
                    readerProgress.forEach { (id, before) -> client.saveProgress(id, ProgressUpdate.reader(before.locator, before.fraction)) }
                }
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(restoreProgress).around(compose)

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

    @Test
    fun searchDetailActionsAndPlayer() = searchDetailActionsAndPlayer(app.session)

    private val saved = mutableMapOf<Long, Progress>()

    /** Comic volumes whose reader position (locator/fraction) the test changes. */
    private val readerProgress = mutableMapOf<Long, Progress>()

    /** Remembers the progress of the items this test may play (the sample movie and the first episode). */
    private suspend fun AntresolClient.rememberProgress() {
        val ids = search("Big Buck").library.map { it.id } +
            search("Test Show").library.flatMap { card -> Episodes.ordered(item(card.id)).take(1).map { it.id } }
        ids.forEach { saved[it] = progress(it) }
    }

    private suspend fun AntresolClient.restoreProgress(id: Long, before: Progress) {
        if (before.hasProgress) {
            saveProgress(id, ProgressUpdate.playback(before.positionMs, before.durationMs))
            if (before.finished) setWatched(id, true)
        } else {
            setWatched(id, false)
        }
    }

    private fun searchDetailActionsAndPlayer(session: SessionManager) {
        runBlocking {
            signIn(session)
            session.requireClient().rememberProgress()
        }
        compose.setContent { AntresolTvTheme { AntresolTvRoot(session) } }
        waitFor(isFocused() and (hasText("Play") or hasText("Resume") or hasAnyDescendant(hasText("Play") or hasText("Resume"))))

        // Search: Up to the nav lands on Home, Left reaches the search pill.
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Find movies, shows, music and books in your library."))
        compose.onNode(hasSetTextAction()).performTextReplacement("Big Buck")
        waitFor(hasText("Movies & TV Shows"))
        shot("10-search")

        // Focus is still on the search pill: Down to the field, Down to the scope pills, Down to the
        // result, OK opens a movie page with Play focused, track facts and the actions.
        key(KeyEvent.KEYCODE_DPAD_DOWN, times = 3)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(isFocused() and (hasText("Play") or hasText("Resume") or hasAnyDescendant(hasText("Play") or hasText("Resume"))))
        waitFor(hasText("Audio:", substring = true))
        Thread.sleep(1500)
        shot("11-movie-detail")

        // Watchlist toggles and toggles back.
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (compose.onAllNodes(isFocused() and hasAnyDescendant(hasText("Play from Beginning")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        waitFor(isFocused() and hasAnyDescendant(hasText("Want to Watch") or hasText("In Want to Watch")))
        val wasListed = compose.onAllNodes(hasText("In Want to Watch"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(if (wasListed) "Want to Watch" else "In Want to Watch"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText(if (wasListed) "In Want to Watch" else "Want to Watch"))

        // Play opens the player: the resume prompt or the controls.
        key(KeyEvent.KEYCODE_DPAD_LEFT, times = 2)
        waitFor(isFocused() and (hasAnyDescendant(hasText("Play") or hasText("Resume"))))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Resume from", substring = true) or hasText("Quality") or hasText("This video can’t be played right now."), timeoutMs = 30_000)
        Thread.sleep(1000)
        shot("12-player")
        // Back hides the controls, then leaves (under Robolectric the clip may already have ended).
        repeat(3) {
            if (compose.onAllNodes(hasText("Audio:", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
                key(KeyEvent.KEYCODE_BACK)
            }
        }
        waitFor(hasText("Audio:", substring = true))
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Quality"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        shot("12b-movie-detail-after-player")

        // A series page: season title, episode cards and "Play S1 E…".
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("Test Show")
        compose.waitUntil(15_000) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.onAllNodes(hasText("Big Buck Demo"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        // From the field: the scope pills, then the result.
        key(KeyEvent.KEYCODE_DPAD_DOWN, times = 2)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Play S1 E", substring = true) or hasText("Resume S1 E", substring = true))
        waitFor(hasText("1. ", substring = true))
        // Down from Play enters the season pills on the selected season, Down again the episodes.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor(isFocused() and hasAnyDescendant(hasText("Season 1")))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        Thread.sleep(1500)
        shot("13-series-detail")
        // OK on an episode opens the player (resume prompt when it has progress).
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Resume from", substring = true) or hasText("Quality") or hasText("Play Now"), timeoutMs = 30_000)
        shot("14-episode-player")
        // Back hides the controls, then leaves the player.
        repeat(3) {
            if (compose.onAllNodes(hasText("Play S1 E", substring = true) or hasText("Resume S1 E", substring = true), useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            ) {
                key(KeyEvent.KEYCODE_BACK)
            }
        }
        waitFor(hasText("Play S1 E", substring = true) or hasText("Resume S1 E", substring = true))
    }

    @Test
    fun musicAudiobookComicAndSettings() {
        val session = app.session
        runBlocking {
            signIn(session)
            val client = session.requireClient()
            client.books("audiobook").items.forEach { saved[it.id] = client.progress(it.id) }
            client.books("comic").items.forEach { series ->
                client.book(series.id).volumes.forEach { readerProgress[it.id] = client.progress(it.id) }
            }
        }
        compose.setContent { AntresolTvTheme { AntresolTvRoot(session) } }
        waitFor(isFocused() and (hasText("Play") or hasText("Resume") or hasAnyDescendant(hasText("Play") or hasText("Resume"))))

        key(KeyEvent.KEYCODE_DPAD_UP)
        moveUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, "Play", maxPresses = 2)
        // Music: header links, genre chips and shelves; the first album card has focus.
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 3)
        waitFor(isFocused() and hasAnyDescendant(hasText("Music")))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("All Albums"))
        waitFor(hasText("Genres"))
        // The nav keeps focus after switching tabs; Down walks into the page (links, genres, first shelf).
        moveUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, "Sample Record")
        Thread.sleep(1500)
        shot("20-music")

        // Album page: Play, Shuffle, Favorite and the track list.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Track Number 1"))
        waitFor(isFocused() and hasAnyDescendant(hasText("Play")))
        Thread.sleep(1500)
        shot("21-album")

        // Play opens Now Playing with the song, its controls and the synced lyrics panel.
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("NOW PLAYING"))
        waitFor(hasText("Lyrics"))
        waitFor(hasContentDescription("Pause") or hasContentDescription("Play"))
        Thread.sleep(2000)
        shot("22-now-playing")
        check(app.audio.currentTrack?.title == "Track Number 1") { "Queue did not start: ${app.audio.currentTrack}" }

        // Back to the music page: the nav shows the now-playing chip.
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Track Number 1"))
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasContentDescription("Now Playing"))
        shot("23-music-now-playing-chip")

        // Books: an audiobook page with Listen/Resume and chapters, then its player.
        key(KeyEvent.KEYCODE_DPAD_UP, times = 3)
        waitFor(isFocused() and hasAnyDescendant(hasText("Music")))
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Manga & Comics"))
        // Down enters the first shelf ("Currently Reading", most recent first); find the audiobook in it.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        findInRow("Chapter Book")
        Thread.sleep(1500)
        shot("24-books")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Chapters"))
        waitFor(isFocused() and (hasAnyDescendant(hasText("Resume")) or hasAnyDescendant(hasText("Listen"))))
        Thread.sleep(1500)
        shot("25-audiobook")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Speed · 1×"))
        waitFor(hasText("Sleep Timer"))
        Thread.sleep(1500)
        shot("26-audiobook-playing")
        check(app.audio.content is AudioContent.Audiobook) { "Audiobook did not start" }
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Chapters"))
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Manga & Comics"))

        // A comic volume: Read/Continue opens the reader; Right turns the page.
        findInRow("Hero Tales")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(isFocused() and (hasAnyDescendant(hasText("Read")) or hasAnyDescendant(hasText("Continue", substring = true))))
        shot("27-comic-volume")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasContentDescription("Page", substring = true))
        Thread.sleep(2000)
        shot("28-comic-reader")
        val before = compose.onAllNodes(hasContentDescription("Page", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().first().config[SemanticsProperties.ContentDescription].first()
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Page", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().firstOrNull()?.config?.get(SemanticsProperties.ContentDescription)?.first() != before
        }
        key(KeyEvent.KEYCODE_BACK)
        waitFor(isFocused() and (hasAnyDescendant(hasText("Read")) or hasAnyDescendant(hasText("Continue", substring = true))))
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Manga & Comics"))

        // Settings from the avatar: profile, playback, language and server.
        key(KeyEvent.KEYCODE_DPAD_UP, times = 3)
        waitFor(isFocused() and hasAnyDescendant(hasText("Books")))
        key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 2)
        waitFor(isFocused() and hasContentDescription("Admin"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Video Quality"))
        waitFor(hasText("Server Version"))
        waitFor(isFocused() and hasAnyDescendant(hasText("Switch Profile")))
        shot("29-settings")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        moveUntilFocused(KeyEvent.KEYCODE_DPAD_UP, "Video Quality")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Used when a video starts", substring = true))
        shot("30-settings-quality")
        key(KeyEvent.KEYCODE_BACK)
        waitFor(isFocused() and hasAnyDescendant(hasText("Video Quality")))

        compose.runOnUiThread { app.audio.stop() }
    }

    /** Moves Left, then Right, along the focused row until the card titled [title] has focus. */
    private fun findInRow(title: String) {
        repeat(6) {
            if (runCatching { focusedTitle() == title }.getOrDefault(false)) return
            key(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        moveUntilFocused(KeyEvent.KEYCODE_DPAD_RIGHT, title, maxPresses = 8)
    }

    /** Presses [code] (at most [maxPresses] times) until the focused element is titled [title]. */
    private fun moveUntilFocused(code: Int, title: String, maxPresses: Int = 5) {
        val seen = mutableListOf<String>()
        repeat(maxPresses) {
            val current = runCatching { focusedTitle() }.getOrDefault("")
            if (current == title) return
            seen += current + runCatching { compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.toString() }.getOrDefault("")
            key(code)
        }
        if (runCatching { focusedTitle() == title }.getOrDefault(false)) return
        shot("failure")
        error("Focus never reached \"$title\"; passed $seen")
    }

    private suspend fun signIn(session: SessionManager) {
        session.restore()
        if (session.state.value !is AuthState.SignedIn) {
            if (session.state.value is AuthState.NoServer) session.connect(serverUrl!!)
            val admin = session.profiles().first { it.hasPassword }
            session.signIn(admin, System.getenv("ANTRESOL_TEST_PASSWORD") ?: "admin123")
        }
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
        try {
            compose.waitUntil(timeoutMs) {
                // Robolectric's clock only moves when told to; let coroutine delays (debounce, timers) elapse.
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
        File(screenshots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

package app.antresol.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
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
 * «Попросить» on the TV against [FakeServer] with requests open, with D-pad key events: a kid finds a title off the
 * library in Search, asks a parent on its page (and sees the state on the results), cannot ask for a title above its
 * age limit, asks for an album from a sheet, reads «My Requests»; an administrator answers the waiting ones from the
 * list. Screenshots go to `tv/build/screenshots/requests-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvRequestsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()
    private val shots = File("build/screenshots").apply { mkdirs() }

    private fun start(kid: Boolean = true, open: Boolean = true) {
        fake.kid = kid
        fake.requestsOpen = open
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

    /** Opens Search from Home and types [query] into its field. */
    private fun searchFor(query: String) {
        key(KeyEvent.KEYCODE_DPAD_UP) // the nav, on Home
        key(KeyEvent.KEYCODE_DPAD_LEFT) // the search pill
        runFor(900)
        key(KeyEvent.KEYCODE_DPAD_DOWN) // into the page: the field
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput(query)
    }

    /** From the field to the first card of the «Find and Ask» row (the library's row is in between). */
    private fun toTheAskRow() {
        // Both answers are in before the D-pad goes down: the row is below the fold, composed once it scrolls in.
        waitFor(hasText("Movies & Shows"))
        waitUntil { fake.requests.any { it.startsWith("GET /api/requests/search") } }
        settle()
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) } // the field → the scope pills → the library row → the ask row
        waitFor(hasText("Find and Ask"))
        waitFor(isFocused() and hasAnyDescendant(hasText("Far Away Film") or hasContentDescription("Far Away Film")))
    }

    /** From the Switch Profile button to the first row of the list: right (the nearest row), then up to the top. */
    private fun toTheFirstSetting(label: String) {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(6) {
            if (compose.onAllNodes(isFocused() and hasAnyDescendant(hasText(label)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
            key(KeyEvent.KEYCODE_DPAD_UP)
        }
        assertFocusedText(label)
    }

    /** Settings from Home: the avatar in the nav. */
    private fun openSettings() {
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 6) // past Music to the avatar
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Switch Profile"))
        waitFor(isFocused() and hasAnyDescendant(hasText("Switch Profile")))
    }

    @Test
    fun aKidAsksForATitleOffTheLibraryOnItsPageAndSeesTheStateOnTheResults() {
        start()
        searchFor("far")
        toTheAskRow()
        settle()
        shot("requests-tv-search")
        assertTrue(fake.requests.contains("GET /api/requests/search?q=far&kind=video"))
        // The kid's search is the catalogue's, not the addons': nothing of them is called.
        assertTrue(fake.requests.none { it.contains("/x/app") })
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("request-ask"))
        assertTrue(fake.requests.contains("GET /api/title/movie/${FakeServer.OUTSIDE_TMDB}"))
        assertFocusedText("Ask a Parent")
        // A kid's page has no way to watch and no watched toggle.
        assertTrue(compose.onAllNodes(hasText("Play"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        shot("requests-tv-title")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("request-status"))
        waitFor(hasText("Waiting for an answer"))
        // The button that asked is the one that shows the state: focus stays on it.
        waitFor(isFocused() and hasTestTag("request-status"))
        assertEquals("pending", fake.kidRequests.values.single().status)
        assertEquals("Far Away Film", fake.kidRequests.values.single().title)
        assertTrue("no second button to ask with", compose.onAllNodes(hasTestTag("request-ask"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        settle()
        shot("requests-tv-asked")
        // Back on the results the row says so, with no new search typed.
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Find and Ask"))
        waitFor(hasText("Waiting")) // the card's badge
        // …and Back lands on the card that was opened.
        waitFor(isFocused() and hasAnyDescendant(hasText("Far Away Film") or hasContentDescription("Far Away Film")))
        settle()
        shot("requests-tv-search-asked")
        assertEquals(1, fake.requests.count { it == "POST /api/requests" })
    }

    @Test
    fun aTitleAboveTheAgeLimitShowsTheServersReasonAndAsksNothing() {
        start()
        searchFor("far")
        toTheAskRow()
        key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 2) // Far Away Show → Grown-up Film
        waitFor(isFocused() and hasAnyDescendant(hasText("Grown-up Film") or hasContentDescription("Grown-up Film")))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("request-ask"))
        waitFor(hasText("This is 16+, and this profile allows up to 6+"))
        settle()
        shot("requests-tv-blocked")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        settle()
        assertTrue(fake.kidRequests.isEmpty())
        assertTrue(fake.requests.none { it == "POST /api/requests" })
    }

    @Test
    fun anAlbumHasNoPageSoItAsksFromASheet() {
        start()
        searchFor("far")
        waitFor(hasText("Movies & Shows"))
        key(KeyEvent.KEYCODE_DPAD_DOWN) // the scope pills
        key(KeyEvent.KEYCODE_DPAD_RIGHT, times = 3) // All → Movies → Shows → Music
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Test Album"))
        settle()
        key(KeyEvent.KEYCODE_DPAD_DOWN) // the only row: the album
        assertTrue(fake.requests.contains("GET /api/requests/search?q=far&kind=music"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("A parent will see your request and answer it.", substring = true))
        assertFocusedText("Ask a Parent")
        settle()
        shot("requests-tv-sheet")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Waiting for an answer"))
        val made = fake.kidRequests.values.single()
        assertEquals("music", made.kind)
        assertEquals("mb-1", made.extId)
        assertEquals("Test Artist", made.subtitle)
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Find and Ask"))
    }

    @Test
    fun myRequestsListsWhatTheKidAskedWithTheAnswers() {
        fake.addRequest("movie", FakeServer.OUTSIDE_TMDB, "Far Away Film")
        fake.addRequest("series", FakeServer.OUTSIDE_SERIES_TMDB, "Far Away Show", status = "approved")
        fake.addRequest("movie", 9003, "Another Far Film", status = "declined", reason = "Too scary for now")
        start()
        openSettings()
        toTheFirstSetting("My Requests")
        settle()
        shot("requests-tv-settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("page/requests"))
        waitFor(hasText("Another Far Film"))
        waitFor(hasText("A parent said no"))
        waitFor(hasText("Approved — coming soon"))
        waitFor(hasText("Waiting for an answer"))
        waitFor(hasText("“Too scary for now”"))
        settle()
        shot("requests-tv-mine")
        // Newest first: the last one asked is the first row.
        assertFocusedText("Another Far Film")
        // OK on a video: its page (the kid's request of it shown there).
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("request-status"))
        waitFor(hasText("A parent said no"))
        waitFor(hasText("“Too scary for now”", substring = true))
        shot("requests-tv-declined")
    }

    @Test
    fun anAdministratorApprovesOrDeclinesFromTheList() {
        fake.addRequest("movie", FakeServer.OUTSIDE_TMDB, "Far Away Film")
        fake.addRequest("series", FakeServer.OUTSIDE_SERIES_TMDB, "Far Away Show")
        fake.addRequest("movie", 9003, "Another Far Film", status = "declined", reason = "Not now")
        start(kid = false)
        openSettings()
        toTheFirstSetting("Requests")
        waitFor(hasText("2 requests waiting"))
        settle()
        shot("requests-tv-admin-settings")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasTestTag("page/requests"))
        waitFor(hasText("Sam", substring = true) or hasText("Kiddo", substring = true))
        waitFor(hasText("Answered in the last 30 days"))
        settle()
        shot("requests-tv-admin")
        // The newest waiting one first; OK offers the answers and, for a video, its page.
        assertFocusedText("Far Away Show")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Approve"))
        assertFocusedText("Approve")
        waitFor(hasText("Open Title Page"))
        shot("requests-tv-admin-panel")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil { fake.kidRequests.values.any { it.title == "Far Away Show" && it.status == "approved" } }
        // The list is read again: the answered one moved down, the other waits.
        waitFor(hasText("Approved"))
        assertFocusedText("Far Away Film")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Decline"))
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedText("Decline")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil { fake.kidRequests.values.any { it.title == "Far Away Film" && it.status == "declined" } }
        waitFor(hasText("No new requests."))
        settle()
        shot("requests-tv-admin-done")
    }

    @Test
    fun withRequestsClosedAKidSeesNoneOfIt() {
        start(open = false)
        searchFor("far")
        waitFor(hasText("Movies & Shows"))
        settle()
        assertTrue(compose.onAllNodes(hasText("Find and Ask"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(fake.requests.none { it.startsWith("GET /api/requests") })
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

    private fun assertFocusedDescription(description: String) {
        waitFor(isFocused() and androidx.compose.ui.test.hasContentDescription(description))
    }

    private fun assertFocusedText(text: String) {
        waitFor(isFocused() and (hasText(text) or hasAnyDescendant(hasText(text))))
    }

    // The rule's own loop: it moves the test clock a frame at a time, and Compose navigates and starts what comes next
    // only on those frames (a bare looper loop stalls once the server's answer lands after the last key press settled).
    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) {
                Thread.sleep(10)
                ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
                if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeBy(16)
                condition()
            }
        } catch (e: Throwable) {
            throw IllegalStateException("${e.message}; requests ${fake.requests.takeLast(12)}", e)
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
            shot("requests-tv-failure")
            throw IllegalStateException("${e.message}; requests ${fake.requests.takeLast(12)}", e)
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

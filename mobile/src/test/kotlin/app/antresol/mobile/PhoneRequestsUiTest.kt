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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.fixtures.FakeServer
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
 * «Попросить» on the phone against [FakeServer] with requests open: a kid finds a title off the library in Search,
 * asks a parent on its page (and sees the state on the results), cannot ask for a title above its age limit, asks for
 * an album from a sheet, reads «My Requests»; an administrator answers the waiting ones from the list. Screenshots go
 * to `mobile/build/screenshots/requests-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneRequestsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolMobileApp>()
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
        val state = session.state.value as AuthState.SignedIn
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).build())
        // Screenshots draw the view on a software canvas, which the poster zoom's hardware layers cannot be drawn on.
        compose.setContent { CompositionLocalProvider(LocalPosterZoom provides false) { AntresolTheme { MainShell(session, state) } } }
        waitFor(hasText("Continue Watching"))
    }

    @After
    fun tearDown() = fake.shutdown()

    private fun searchFor(query: String) {
        compose.onNode(hasTestTag("tab-Search")).performClick()
        waitFor(hasTestTag("search-field"))
        compose.onNode(hasTestTag("search-field")).performTextInput(query)
    }

    /** The avatar's sheet, and its [entry]. */
    private fun openAccountEntry(entry: String) {
        compose.onAllNodes(hasContentDescription("Account: Alex")).onFirst().performClick()
        waitFor(hasTestTag(entry))
        compose.onNode(hasTestTag(entry)).performClick()
    }

    @Test
    fun aKidAsksForATitleOffTheLibraryOnItsPageAndSeesTheStateOnTheResults() {
        start()
        searchFor("far")
        waitFor(hasText("Find and Ask"))
        waitFor(titled("Far Away Film"))
        waitForTheLibrarysResults()
        settle()
        shot("requests-phone-search")
        assertTrue(fake.requests.contains("GET /api/requests/search?q=far&kind=video"))
        // The kid's search is the catalogue's, not the addons': nothing of them is called.
        assertTrue(fake.requests.none { it.contains("/x/app") })

        compose.onAllNodes(titled("Far Away Film"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("request-ask"))
        assertTrue(fake.requests.contains("GET /api/title/movie/${FakeServer.OUTSIDE_TMDB}"))
        // A kid's page has no way to watch, no watchlist and no watched toggle.
        assertTrue(compose.onAllNodes(hasTestTag("detail-play")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("toggle-watchlist")).fetchSemanticsNodes().isEmpty())
        settle()
        shot("requests-phone-title")
        compose.onNode(hasTestTag("request-ask")).performClick()
        waitFor(hasTestTag("request-status"))
        waitFor(hasText("Waiting for an answer"))
        assertEquals("pending", fake.kidRequests.values.single().status)
        assertTrue(compose.onAllNodes(hasTestTag("request-ask")).fetchSemanticsNodes().isEmpty())
        settle()
        shot("requests-phone-asked")

        // Back on the results the card says so, with no new search typed.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasText("Find and Ask"))
        waitFor(hasText("Waiting")) // the card's badge
        settle()
        shot("requests-phone-search-asked")
        assertEquals(1, fake.requests.count { it == "POST /api/requests" })
    }

    @Test
    fun aTitleAboveTheAgeLimitShowsTheServersReasonAndAsksNothing() {
        start()
        searchFor("far")
        waitFor(titled("Grown-up Film"))
        waitForTheLibrarysResults()
        settle()
        compose.onAllNodes(titled("Grown-up Film"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("request-ask"))
        waitFor(hasText("This is 16+, and this profile allows up to 6+"))
        settle()
        shot("requests-phone-blocked")
        compose.onNode(hasTestTag("request-ask")).performClick()
        settle()
        assertTrue(fake.kidRequests.isEmpty())
        assertTrue(fake.requests.none { it == "POST /api/requests" })
    }

    @Test
    fun anAlbumHasNoPageSoItAsksFromASheet() {
        start()
        searchFor("far")
        waitFor(hasText("Find and Ask"))
        // the filter pill, not the bottom bar's tab of the same name
        compose.onNode(hasText("Music") and !hasTestTag("tab-Music")).performClick()
        waitFor(titled("Test Album"))
        assertTrue(fake.requests.contains("GET /api/requests/search?q=far&kind=music"))
        compose.onAllNodes(titled("Test Album"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("ask-sheet"))
        waitFor(hasText("A parent will see your request and answer it."))
        settle()
        shot("requests-phone-sheet")
        compose.onNode(hasTestTag("request-ask")).performClick()
        waitFor(hasTestTag("request-status"))
        val made = fake.kidRequests.values.single()
        assertEquals("music", made.kind)
        assertEquals("mb-1", made.extId)
        assertEquals("Test Artist", made.subtitle)
        shot("requests-phone-sheet-asked")
    }

    @Test
    fun myRequestsListsWhatTheKidAskedWithTheAnswers() {
        fake.addRequest("movie", FakeServer.OUTSIDE_TMDB, "Far Away Film")
        fake.addRequest("series", FakeServer.OUTSIDE_SERIES_TMDB, "Far Away Show", status = "approved")
        fake.addRequest("movie", 9003, "Another Far Film", status = "declined", reason = "Too scary for now")
        start()
        openAccountEntry("account-my-requests")
        waitFor(hasTestTag("my-requests"))
        waitFor(hasText("Another Far Film"))
        waitFor(hasText("A parent said no"))
        waitFor(hasText("Approved — coming soon"))
        waitFor(hasText("Waiting for an answer"))
        waitFor(hasText("“Too scary for now”"))
        settle()
        shot("requests-phone-mine")
        // A video opens its page, with the state of the request on it.
        compose.onAllNodes(hasText("Another Far Film"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("request-status"))
        waitFor(hasText("A parent said no"))
        shot("requests-phone-declined")
    }

    @Test
    fun anAdministratorApprovesOrDeclinesFromTheList() {
        fake.addRequest("movie", FakeServer.OUTSIDE_TMDB, "Far Away Film")
        fake.addRequest("series", FakeServer.OUTSIDE_SERIES_TMDB, "Far Away Show")
        fake.addRequest("movie", 9003, "Another Far Film", status = "declined", reason = "Not now")
        start(kid = false)
        compose.onAllNodes(hasContentDescription("Account: Alex")).onFirst().performClick()
        waitFor(hasText("2 requests waiting", substring = true))
        shot("requests-phone-admin-account")
        compose.onNode(hasTestTag("account-requests")).performClick()
        waitFor(hasTestTag("requests"))
        waitFor(hasText("Answered in the last 30 days"))
        waitFor(hasText("Kiddo", substring = true))
        settle()
        shot("requests-phone-admin")
        // Approve the first waiting one (the newest, the show).
        compose.onAllNodes(hasTestTag("request-approve")).onFirst().performClick()
        waitUntil { fake.kidRequests.values.any { it.title == "Far Away Show" && it.status == "approved" } }
        // Decline the other with a reason.
        waitUntil { compose.onAllNodes(hasTestTag("request-decline")).fetchSemanticsNodes().size == 1 }
        compose.onNode(hasTestTag("request-decline")).performClick()
        waitFor(hasTestTag("decline-form"))
        settle()
        shot("requests-phone-admin-decline")
        compose.onNode(hasTestTag("decline-reason")).performTextInput("Not today")
        compose.onNode(hasTestTag("decline-confirm")).performClick()
        waitUntil { fake.kidRequests.values.any { it.title == "Far Away Film" && it.status == "declined" && it.reason == "Not today" } }
        waitFor(hasText("No new requests."))
        settle()
        shot("requests-phone-admin-done")
    }

    @Test
    fun withRequestsClosedAKidSeesNoneOfIt() {
        start(open = false)
        searchFor("far")
        waitFor(hasText("Movies & Shows"))
        settle()
        assertTrue(compose.onAllNodes(hasText("Find and Ask"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(fake.requests.none { it.startsWith("GET /api/requests") })
        compose.onAllNodes(hasContentDescription("Account: Alex")).onFirst().performClick()
        waitFor(hasTestTag("account-settings"))
        assertTrue(compose.onAllNodes(hasTestTag("account-my-requests")).fetchSemanticsNodes().isEmpty())
    }

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

    /**
     * The library's results arrive beside the kid's titles to ask for, and its shelf goes above them: a tap made before it is
     * in lands on a card that is moving (a tap lost on a loaded machine, a different one each run).
     */
    private fun waitForTheLibrarysResults() = waitFor(titled("Movie 1"))

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
            shot("requests-phone-failure")
            throw IllegalStateException("${e.message}", e)
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

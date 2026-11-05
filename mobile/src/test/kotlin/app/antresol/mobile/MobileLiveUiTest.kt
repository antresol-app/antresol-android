package app.antresol.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.mobile.ui.root.AntresolMobileRoot
import app.antresol.ui.theme.AntresolTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
 * Drives the real phone UI against a live server with taps and saves screenshots to
 * `mobile/build/screenshots`. Runs only with `ANTRESOL_TEST_SERVER` set ("1" = http://127.0.0.1:8420);
 * signs in as profile 1 with `ANTRESOL_TEST_PASSWORD` (default `admin123`). The watchlist toggle is
 * flipped and flipped back, so the server ends as it started.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileLiveUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val serverUrl: String? = System.getenv("ANTRESOL_TEST_SERVER")?.let { if (it == "1") "http://127.0.0.1:8420" else it }
    private val password = System.getenv("ANTRESOL_TEST_PASSWORD") ?: "admin123"
    private val app get() = ApplicationProvider.getApplicationContext<AntresolMobileApp>()
    private val screenshots = File("build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        assumeTrue("ANTRESOL_TEST_SERVER not set", serverUrl != null)
    }

    @Test
    @Config(sdk = [34], qualifiers = "en-w393dp-h852dp-port-xxhdpi")
    fun phoneOnboardingBrowseDetailSearch() {
        val session = app.session
        runBlocking {
            session.restore()
            if (session.state.value is AuthState.SignedIn) session.switchProfile()
            if (session.state.value !is AuthState.NoServer) session.changeServer()
        }
        compose.setContent { AntresolTheme { AntresolMobileRoot(session) } }

        // Server address: a bad address shows an error, the real one opens the profile picker.
        waitFor(hasText("Connect to your server"))
        val field = compose.onNode(hasTestTag("server-address"))
        field.performTextReplacement("127.0.0.1:1")
        field.performImeAction()
        waitFor(hasText("Can’t reach the server", substring = true))
        shot("phone-00-server-error")
        field.performTextReplacement(serverUrl!!.removePrefix("http://"))
        field.performImeAction()

        // Profile picker → password → signed in.
        waitFor(hasText("Who’s watching?"))
        shot("phone-01-profiles")
        compose.onNode(hasTestTag("profile-1")).performClick()
        waitFor(hasText("Enter the password for Admin"))
        compose.onNode(hasTestTag("password")).performTextReplacement(password)
        shot("phone-02-password")
        compose.onNode(hasTestTag("password")).performImeAction()

        // Home: hero with Play and Details, shelves below.
        waitFor(hasText("Details"))
        Thread.sleep(2500) // artwork
        shot("phone-03-home")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("Recently Played"))
        shot("phone-04-home-shelves")

        // Movies grid with sort pills; A–Z re-sorts.
        tab("Movies")
        waitFor(hasText("A–Z"))
        compose.onNode(hasText("A–Z")).performClick()
        waitFor(hasText("Big Buck Demo"))
        Thread.sleep(1500)
        shot("phone-05-movies")

        // Movie detail; the watchlist toggle reaches the server, then is flipped back.
        compose.onAllNodes(hasText("Big Buck Demo") and hasClickAction().not(), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("toggle-watchlist"))
        Thread.sleep(1500)
        shot("phone-06-movie-detail")
        val movieId = 19L
        val before = runBlocking { session.requireClient().item(movieId).inWatchlist }
        compose.onNode(hasTestTag("toggle-watchlist")).performClick()
        compose.waitUntil(10_000) { runBlocking { session.requireClient().item(movieId).inWatchlist } != before }
        compose.onNode(hasTestTag("toggle-watchlist")).performClick()
        compose.waitUntil(10_000) { runBlocking { session.requireClient().item(movieId).inWatchlist } == before }
        compose.onNode(hasContentDescription("Back")).performClick()

        // TV Shows → series detail with seasons and episodes.
        tab("TV Shows")
        waitFor(hasText("Test Show"))
        compose.onAllNodes(hasText("Test Show"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasText("Season 2"))
        Thread.sleep(1500)
        shot("phone-07-series-detail")
        compose.onNode(hasContentDescription("Back")).performClick()

        tab("Music")
        waitFor(hasText("All Albums"))
        Thread.sleep(1500)
        shot("phone-08-music")
        compose.onNode(hasText("All Albums")).performClick()
        waitFor(hasText("By Artist"))
        Thread.sleep(1000)
        shot("phone-09-albums")
        compose.onNode(hasContentDescription("Back")).performClick()

        tab("Books")
        waitFor(hasText("Manga & Comics"))
        Thread.sleep(1500)
        shot("phone-10-books")

        // Search: browse tiles, then results for a query.
        compose.onNode(hasTestTag("tab-Search")).performClick()
        waitFor(hasText("Browse"))
        shot("phone-11-search-browse")
        compose.onNode(hasTestTag("search-field")).performTextReplacement("test")
        waitFor(hasText("Movies & TV Shows"))
        Thread.sleep(1500)
        shot("phone-12-search-results")

        // Avatar → account sheet → switch profile.
        compose.onAllNodes(hasContentDescription("Account: Admin")).onFirst().performClick()
        waitFor(hasTestTag("account-switch"))
        shot("phone-13-account")
        compose.onNode(hasTestTag("account-switch")).performClick()
        waitFor(hasText("Who’s watching?"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en-w1024dp-h768dp-land-xhdpi")
    fun tabletHomeAndGrid() {
        val session = app.session
        runBlocking {
            session.restore()
            if (session.state.value is AuthState.SignedIn) session.switchProfile()
            session.connect(serverUrl!!)
            session.signIn(Profile(id = 1, name = "Admin", hasPassword = true), password)
        }
        compose.setContent { AntresolTheme { AntresolMobileRoot(session) } }
        waitFor(hasText("Details"))
        Thread.sleep(2500)
        shot("tablet-01-home")
        tab("Movies")
        waitFor(hasText("Big Buck Demo"))
        Thread.sleep(1500)
        shot("tablet-02-movies")
        compose.onAllNodes(hasText("Test Film"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("toggle-watched"))
        Thread.sleep(1500)
        shot("tablet-03-detail")
        runBlocking { session.switchProfile() }
    }

    /**
     * Playlists: create one from the Playlists page, add an album to it, move and remove a song,
     * rename it and delete it. Every step is checked on the server; the playlist is deleted at the
     * end (also when a step fails).
     */
    @Test
    @Config(sdk = [34], qualifiers = "en-w393dp-h852dp-port-xxhdpi")
    fun phonePlaylists() {
        val session = app.session
        val name = "UI Test Mix"
        val client = runBlocking {
            session.restore()
            if (session.state.value is AuthState.SignedIn) session.switchProfile()
            session.connect(serverUrl!!)
            session.signIn(Profile(id = 1, name = "Admin", hasPassword = true), password)
            session.requireClient()
        }
        fun created() = runBlocking { client.playlists().items.filter { it.name == name || it.name == "$name 2" } }
        try {
            compose.setContent { AntresolTheme { AntresolMobileRoot(session) } }
            waitFor(hasText("Details"))
            tab("Music")
            waitFor(hasText("Playlists"))
            compose.onAllNodes(hasText("Playlists") and hasClickAction()).onFirst().performClick()
            waitFor(hasTestTag("playlist-create"))
            compose.onNode(hasTestTag("playlist-create")).performClick()
            waitFor(hasTestTag("playlist-name"))
            compose.onNode(hasTestTag("playlist-name")).performTextReplacement(name)
            compose.onNode(hasTestTag("playlist-name-confirm")).performClick()
            // The new, empty playlist opens.
            waitFor(hasTestTag("playlist-edit"))
            val playlistId = created().single().id
            shot("phone-20-playlist-new")
            compose.onNode(hasContentDescription("Back")).performClick()
            compose.onNode(hasContentDescription("Back")).performClick()

            // Album → Add to Playlist → the new playlist.
            compose.onNode(hasText("All Albums")).performClick()
            waitFor(hasText("Test Album"))
            compose.onAllNodes(hasText("Test Album"), useUnmergedTree = true).onFirst().performClick()
            waitFor(hasTestTag("album-add-to-playlist"))
            compose.onNode(hasTestTag("album-add-to-playlist")).performClick()
            waitFor(hasTestTag("playlist-$playlistId"))
            shot("phone-21-add-to-playlist")
            compose.onNode(hasTestTag("playlist-$playlistId")).performClick()
            compose.waitUntil(10_000) { runBlocking { client.playlist(playlistId).tracks.isNotEmpty() } }
            val tracks = runBlocking { client.playlist(playlistId).tracks.map { it.id } }
            assertTrue("album tracks added", tracks.size >= 2)
            compose.onNode(hasContentDescription("Back")).performClick()
            compose.onNode(hasContentDescription("Back")).performClick()

            // The playlist: move the first song down, remove the new first one, rename.
            compose.onAllNodes(hasText("Playlists") and hasClickAction()).onFirst().performClick()
            waitFor(hasText(name))
            compose.onAllNodes(hasText(name), useUnmergedTree = true).onFirst().performClick()
            waitFor(hasTestTag("playlist-edit"))
            compose.onNode(hasTestTag("playlist-edit")).performClick()
            waitFor(hasTestTag("playlist-down-0"))
            shot("phone-22-playlist-editing")
            compose.onNode(hasTestTag("playlist-down-0")).performClick()
            val moved = listOf(tracks[1], tracks[0]) + tracks.drop(2)
            compose.waitUntil(10_000) { runBlocking { client.playlist(playlistId).tracks.map { it.id } } == moved }
            compose.onNode(hasTestTag("playlist-remove-0")).performClick()
            compose.waitUntil(10_000) { runBlocking { client.playlist(playlistId).tracks.map { it.id } } == moved.drop(1) }
            compose.onNode(hasTestTag("playlist-rename")).performClick()
            waitFor(hasTestTag("playlist-name"))
            compose.onNode(hasTestTag("playlist-name")).performTextReplacement("$name 2")
            compose.onNode(hasTestTag("playlist-name-confirm")).performClick()
            compose.waitUntil(10_000) { runBlocking { client.playlist(playlistId).playlist.name } == "$name 2" }

            // Delete (after the confirmation) closes the page.
            compose.onNode(hasTestTag("playlist-delete")).performClick()
            waitFor(hasTestTag("playlist-delete-confirm"))
            compose.onNode(hasTestTag("playlist-delete-confirm")).performClick()
            waitFor(hasTestTag("playlist-create"))
            compose.waitUntil(10_000) { created().isEmpty() }
            shot("phone-23-playlists-after-delete")
        } finally {
            runBlocking {
                created().forEach { runCatching { client.deletePlaylist(it.id) } }
                session.switchProfile()
            }
        }
    }

    private fun tab(label: String) {
        compose.onNode(hasTestTag("tab-" + when (label) {
            "TV Shows" -> "Shows"
            else -> label
        })).performClick()
        compose.waitForIdle()
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMs) {
            // Robolectric's paused main looper only runs delayed work (the search debounce) when its clock moves.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
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

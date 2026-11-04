package app.antresol.mobile

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Profile
import app.antresol.core.model.Progress
import app.antresol.core.model.ProgressUpdate
import app.antresol.core.session.AuthState
import app.antresol.mobile.playback.PlaybackService
import app.antresol.mobile.player.ResumeMode
import app.antresol.mobile.player.VideoPlayerActivity
import app.antresol.mobile.ui.root.AntresolMobileRoot
import app.antresol.ui.theme.AntresolTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Live playback flows on a phone against a real server (only with `ANTRESOL_TEST_SERVER`): the
 * video player activity, music through the Media3 service (mini-player, Now Playing, queue,
 * star), an audiobook with chapters, the comic reader and Settings. Progress and stars the test
 * touches are put back afterwards. Screenshots go to `mobile/build/screenshots`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w393dp-h852dp-port-xxhdpi")
class MobilePlaybackLiveUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val saved = mutableMapOf<Long, Progress>()

    /** Final progress writes run after the test body; wait for them, then restore what was there. */
    private val restoreProgress = object : ExternalResource() {
        override fun after() {
            if (saved.isEmpty()) return
            repeat(30) {
                ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
                Thread.sleep(100)
            }
            runBlocking { app.session.client?.let { client -> saved.forEach { (id, before) -> client.restore(id, before) } } }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(restoreProgress).around(compose)

    private val serverUrl: String? = System.getenv("ANTRESOL_TEST_SERVER")?.let { if (it == "1") "http://127.0.0.1:8420" else it }
    private val password = System.getenv("ANTRESOL_TEST_PASSWORD") ?: "admin123"
    private val app get() = ApplicationProvider.getApplicationContext<AntresolMobileApp>()
    private val screenshots = File("build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        assumeTrue("ANTRESOL_TEST_SERVER not set", serverUrl != null)
        signIn()
    }

    @Test
    @Config(sdk = [34], qualifiers = "en-w852dp-h393dp-land-xxhdpi")
    fun videoPlayerActivity() {
        val client = app.session.requireClient()
        val movieId = runBlocking { client.search("Big Buck Demo").library.first().id }
        remember(movieId)
        val intent = Intent(app, VideoPlayerActivity::class.java).putExtra("itemId", movieId).putExtra("resume", ResumeMode.FromStart.name)
        ActivityScenario.launch<VideoPlayerActivity>(intent).use { scenario ->
            // The controls (or the error panel when Robolectric cannot decode) replace the spinner.
            waitUntil(30_000) {
                compose.onAllNodes(hasTestTag("player-play-pause") or hasText("Try Again"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            Thread.sleep(500)
            shot(scenario, "play-01-video-controls")
            if (compose.onAllNodes(hasContentDescription("Quality"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNode(hasContentDescription("Quality")).performClick()
                waitFor(hasText("Original"))
                shot(scenario, "play-02-video-quality")
            }
        }
    }

    @Test
    fun musicAudiobookComicAndSettings() {
        bindPlaybackService()
        val client = app.session.requireClient()
        compose.setContent { AntresolTheme { AntresolMobileRoot(app.session) } }
        waitFor(hasText("Details"))

        // Album → tap the first track → the mini-player appears.
        tab("Music")
        waitFor(hasText("Songs"))
        shot("play-10-music")
        compose.onNode(hasText("All Albums")).performClick()
        waitFor(hasText("Test Album"))
        compose.onAllNodes(hasText("Test Album"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("play-all"))
        val album = runBlocking { client.album(client.albums().items.first { it.title == "Test Album" }.id) }
        val track = album.tracks.first()
        compose.onNode(hasTestTag("track-${track.id}")).performClick()
        waitFor(hasTestTag("mini-player"))
        Thread.sleep(1000)
        shot("play-11-album-mini-player")

        // Now Playing: title, star (flipped on the server and back), queue.
        compose.onNode(hasTestTag("mini-player")).performClick()
        waitFor(hasTestTag("now-playing-title"))
        Thread.sleep(1000)
        shot("play-12-now-playing")
        val starred = track.starred
        compose.onNode(hasTestTag("now-playing-star")).performClick()
        waitUntil(10_000) { runBlocking { client.track(track.id).starred } != starred }
        compose.onNode(hasTestTag("now-playing-star")).performClick()
        waitUntil(10_000) { runBlocking { client.track(track.id).starred } == starred }
        compose.onNode(hasTestTag("panel-queue")).performClick()
        waitFor(hasTestTag("queue"))
        shot("play-13-queue")
        if (track.hasLyrics) {
            compose.onNode(hasTestTag("panel-lyrics")).performClick()
            waitFor(hasTestTag("lyrics"))
            shot("play-14-lyrics")
        }
        compose.onNode(hasTestTag("now-playing-sleep")).performClick()
        waitFor(hasText("End of Track"))
        shot("play-15-sleep-timer")
        compose.onNode(hasText("15 minutes")).performClick()
        compose.onNode(hasTestTag("now-playing-close")).performClick()
        waitFor(hasTestTag("mini-player"))

        // Audiobook: Resume plays through the same service; Now Playing shows chapters and speed.
        val audiobookId = runBlocking { client.books("audiobook").items.first { it.title == "Chapter Book" }.id }
        remember(audiobookId)
        tab("Books")
        waitFor(hasText("Audiobooks"))
        compose.onAllNodes(hasText("Audiobooks")).onFirst().performClick()
        waitFor(hasText("Chapter Book"))
        compose.onAllNodes(hasText("Chapter Book"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("book-listen"))
        Thread.sleep(1000)
        shot("play-20-audiobook-detail")
        compose.onNode(hasTestTag("book-listen")).performClick()
        waitUntil(15_000) { app.playback.current?.bookId == audiobookId }
        compose.onNode(hasTestTag("mini-player")).performClick()
        waitFor(hasTestTag("now-playing-speed"))
        Thread.sleep(1000)
        shot("play-21-audiobook-now-playing")
        compose.onNode(hasTestTag("panel-chapters")).performClick()
        waitFor(hasTestTag("chapters"))
        shot("play-22-chapters")
        compose.onNode(hasTestTag("now-playing-speed")).performClick()
        waitFor(hasText("1.5×"))
        shot("play-23-speed")
        compose.onNode(hasText("1×") and hasTestTag("now-playing-speed").not()).performClick()
        compose.onNode(hasTestTag("now-playing-close")).performClick()
        compose.runOnUiThread { app.playback.stop() }
        tab("Books") // re-selecting the tab returns to its root

        // Comic: series → Continue → reader.
        val volumeId = runBlocking { client.book(client.books("comic").items.first().id).volumes.first().id }
        remember(volumeId)
        waitFor(hasText("Manga & Comics"))
        compose.onAllNodes(hasText("Manga & Comics")).onFirst().performClick()
        waitFor(hasText("Hero Tales"))
        compose.onAllNodes(hasText("Hero Tales"), useUnmergedTree = true).onFirst().performClick()
        waitFor(hasTestTag("book-read"))
        compose.onNode(hasTestTag("book-read")).performClick()
        waitFor(hasTestTag("comic-pager"))
        Thread.sleep(2000)
        shot("play-30-comic-reader")
        compose.onNode(hasTestTag("comic-pager")).performClick()
        waitFor(hasTestTag("reader-page"))
        shot("play-31-comic-reader-chrome")
        compose.onNode(hasContentDescription("Back")).performClick()
        waitFor(hasTestTag("book-read"))

        // Settings from the account sheet.
        tab("Home")
        waitFor(hasText("Details"))
        compose.onAllNodes(hasContentDescription("Admin")).onFirst().performClick()
        waitFor(hasTestTag("account-settings"))
        compose.onNode(hasTestTag("account-settings")).performClick()
        waitFor(hasText("This device"))
        waitFor(hasText("0.3.0", substring = true))
        shot("play-40-settings")
        compose.onNode(hasTestTag("settings")).performScrollToNode(hasText("App Version"))
        shot("play-41-settings-bottom")
    }

    private fun signIn() = runBlocking {
        val session = app.session
        session.restore()
        if (session.state.value is AuthState.SignedIn) session.switchProfile()
        session.connect(serverUrl!!)
        session.signIn(Profile(id = 1, name = "Admin", hasPassword = true), password)
    }

    /** Robolectric does not start bound services; hand MediaController the real service's binder. */
    private fun bindPlaybackService() {
        val service = Robolectric.buildService(PlaybackService::class.java).create().get()
        val component = ComponentName(app, PlaybackService::class.java)
        val binder = service.onBind(Intent(MediaSessionService.SERVICE_INTERFACE).setComponent(component))
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
    }

    private fun remember(itemId: Long) = runBlocking { saved[itemId] = app.session.requireClient().progress(itemId) }

    private suspend fun AntresolClient.restore(id: Long, before: Progress) {
        when {
            !before.hasProgress -> setWatched(id, false)
            before.locator.isNotBlank() -> saveProgress(id, ProgressUpdate.reader(before.locator, before.fraction))
            else -> saveProgress(id, ProgressUpdate.playback(before.positionMs, before.durationMs))
        }
        if (before.finished) setWatched(id, true)
    }

    private fun tab(label: String) {
        compose.onNode(hasTestTag("tab-$label")).performClick()
        compose.waitForIdle()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            condition()
        }
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 15_000) = waitUntil(timeoutMs) {
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        save(compose.activity.window.decorView, name)
    }

    private fun shot(scenario: ActivityScenario<VideoPlayerActivity>, name: String) {
        compose.waitForIdle()
        scenario.onActivity { save(it.window.decorView, name) }
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(screenshots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

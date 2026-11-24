package app.antresol.tv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.fixtures.FakeServer
import app.antresol.tv.ui.shell.MainShell
import app.antresol.tv.ui.shell.TAB_RESET_MS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * What a tab switch costs on the TV, against [FakeServer]: the API requests it sends. A tab left for longer than
 * [TAB_RESET_MS] goes back to its root, but keeps what it loaded: going round the tabs again, slowly, asks the
 * server for nothing but what is refreshed in place on purpose whenever it is shown again (Home's Continue Watching,
 * the «ТВ» tab's channel list). Before, every tab came back empty and loaded again behind a spinner.
 * The counts are printed (`TAB-COST`) for the performance notes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvTabSwitchCostUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolTvApp>()

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
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).build())
        compose.setContent {
            val state by session.state.collectAsState()
            (state as? AuthState.SignedIn)?.let { AntresolTvTheme { MainShell(session, it) } }
        }
        waitFor(hasText("The Test Show") and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text))
        waitFor(isFocused())
    }

    @After
    fun tearDown() = fake.shutdown()

    @Test
    fun goingRoundTheTabsAgainSlowlyKeepsTheirData() {
        key(KeyEvent.KEYCODE_DPAD_UP) // Play → the nav's Home pill
        // Home → Movies → Shows → ТВ → Music and back to Home, twice, resting on each tab longer than the reset.
        val moves = listOf("Movies", "Shows", "TV", "Music").map { it to KeyEvent.KEYCODE_DPAD_RIGHT } +
            listOf("Tv", "Shows", "Movies", "Home").map { it to KeyEvent.KEYCODE_DPAD_LEFT }
        val rounds = (1..2).map { round ->
            moves.map { (name, code) ->
                val before = api().size
                key(code)
                rest(TAB_RESET_MS + 1_000)
                val sent = api().drop(before)
                println("TAB-COST tv round $round -> $name: api=${sent.size} $sent")
                sent
            }
        }
        val second = rounds[1].flatten().filterNot { it.startsWith("GET /api/channels") || it == "GET /api/home" }
        assertEquals("the second round asked the server again: $second", emptyList<String>(), second)
    }

    private fun api() = fake.requests.filter { it.startsWith("GET /api/") && !it.startsWith("GET /api/images/") && !it.contains("/photo") }

    /** The clock and the looper run for [ms], so the tab opens, loads and the reset of the tab left fires. */
    private fun rest(ms: Long) {
        var left = ms
        while (left > 0) {
            Thread.sleep(2)
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(100)
            left -= 100
        }
        compose.waitForIdle()
        repeat(10) {
            Thread.sleep(20)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        }
        compose.waitForIdle()
    }

    private fun key(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
        Thread.sleep(150)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

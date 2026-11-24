package app.antresol.mobile

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.fixtures.FakeServer
import app.antresol.mobile.ui.components.LocalPosterZoom
import app.antresol.mobile.ui.shell.MainShell
import app.antresol.mobile.ui.shell.NavTab
import app.antresol.ui.theme.AntresolTheme
import coil.EventListener
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.request.SuccessResult
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
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a tab switch costs on the phone, against [FakeServer]: the API requests it sends and the artwork it decodes
 * again. A tab the user comes back to keeps its data (its ViewModel lives in the tab's saved back stack), so going
 * round all the tabs a second time asks the server for nothing but the channel list, which Home's «On Air Now» and the
 * «ТВ» tab refresh in place on purpose when shown again after a few seconds (`ChannelsViewModel.startRefreshing`).
 * The counts are printed (`TAB-COST`) for the performance notes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneTabSwitchCostUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeServer()
    private val app get() = ApplicationProvider.getApplicationContext<AntresolMobileApp>()
    private val images = AtomicInteger()
    private val imagesFromNetwork = AtomicInteger()

    @After
    fun tearDown() = fake.shutdown()

    @Test
    fun goingRoundTheTabsAgainAsksTheServerForNothing() {
        fake.start()
        val session = app.session
        runBlocking {
            session.restore()
            if (session.state.value !is AuthState.NoServer) session.changeServer()
            session.connect(fake.baseUrl)
            session.signIn(Profile(1, "Alex", "grad-3"), null)
        }
        val state = session.state.value as AuthState.SignedIn
        val counting = object : EventListener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                images.incrementAndGet()
                if (result.dataSource == DataSource.NETWORK) imagesFromNetwork.incrementAndGet()
            }
        }
        coil.Coil.setImageLoader(app.newImageLoader().newBuilder().crossfade(false).eventListener(counting).build())
        compose.setContent { CompositionLocalProvider(LocalPosterZoom provides false) { AntresolTheme { MainShell(session, state) } } }
        waitFor(hasText("Continue Watching"))
        settle()

        val tabs = listOf(NavTab.Movies, NavTab.Shows, NavTab.Tv, NavTab.Music, NavTab.Books, NavTab.Home)
        val rounds = (1..2).map { round ->
            tabs.map { tab ->
                val before = api().size
                val imagesBefore = images.get()
                val networkBefore = imagesFromNetwork.get()
                compose.onNode(hasTestTag("tab-${tab.name}")).performClick()
                settle()
                val sent = api().drop(before)
                println("TAB-COST phone round $round ${tab.name}: api=${sent.size} $sent images=${images.get() - imagesBefore} fromNetwork=${imagesFromNetwork.get() - networkBefore}")
                sent
            }
        }
        // The second round: every tab is still loaded.
        val second = rounds[1].flatten().filterNot { it == "GET /api/channels" }
        assertEquals("the second round asked the server again: $second", emptyList<String>(), second)
        assertTrue(rounds[0].flatten().isNotEmpty())
    }

    /** The API requests so far: artwork, the media key and the always-on background calls left out. */
    private fun api() = fake.requests.filter { it.startsWith("GET /api/") && !it.startsWith("GET /api/images/") && !it.contains("/photo") }

    private fun settle() {
        repeat(30) {
            Thread.sleep(30)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        compose.waitForIdle()
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

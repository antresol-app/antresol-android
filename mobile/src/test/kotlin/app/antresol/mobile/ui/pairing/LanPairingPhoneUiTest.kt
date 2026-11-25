package app.antresol.mobile.ui.pairing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.discovery.DiscoveredServer
import app.antresol.core.discovery.DiscoveryState
import app.antresol.core.discovery.ServerFinder
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.PairApproved
import app.antresol.core.model.PairDevice
import app.antresol.core.model.PairLookup
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthState
import app.antresol.core.session.InMemorySessionStore
import app.antresol.core.session.SessionManager
import app.antresol.mobile.ui.root.AntresolMobileRoot
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolTheme
import androidx.compose.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * The phone's server list (LAN discovery) and «Sign in a TV» (scan → device → profile → Approve /
 * Decline). Screenshots: `mobile/build/screenshots/lan-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanPairingPhoneUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shots = File("build/screenshots").apply { mkdirs() }
    private val serverId = "0123456789abcdef0123456789abcdef"
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/health" -> MockResponse().setBody("""{"ok":true,"serverId":"$serverId","version":"1.4.0"}""")
                "/api/setup" -> MockResponse().setBody("""{"needsSetup":false}""")
                "/api/profiles" -> MockResponse().setBody("""[{"id":1,"name":"Alex","avatar":"grad-1","hasPassword":true}]""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun foundServersConnectLikeATypedAddress() {
        val session = SessionManager(
            InMemorySessionStore(deviceId = "test-phone-device-01"),
            { DeviceInfo(it, "Pixel", "Pixel", "1.0") },
            { "en" },
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        runBlocking { session.restore() }
        val base = server.url("/").toString().trimEnd('/')
        val state = MutableStateFlow(DiscoveryState(emptyList(), searching = true))
        compose.setContent { AntresolTheme { AntresolMobileRoot(session, ServerFinder { state }) } }
        compose.waitForIdle()
        compose.onNodeWithText("Looking for servers", substring = true).assertIsDisplayed()
        shot("lan-phone-server-searching")
        state.value = DiscoveryState(
            listOf(
                DiscoveredServer(serverId, "Home Media", "1.4.0", base),
                DiscoveredServer("ffffffffffffffffffffffffffffffff", "Dacha", "1.3.2", "http://192.168.1.77:8420"),
            ),
            searching = false,
        )
        compose.waitForIdle()
        compose.onNodeWithText("ON YOUR NETWORK").assertIsDisplayed()
        compose.onNodeWithText("OR ENTER THE ADDRESS").assertExists()
        compose.onNodeWithText("192.168.1.77:8420 · version 1.3.2").assertIsDisplayed()
        shot("lan-phone-server-found")

        compose.onNodeWithTag("server-$serverId").performClick()
        waitFor(hasText("Alex"))
        val chooser = session.state.value as AuthState.ChooseProfile
        assertEquals(base, chooser.serverUrl)
        assertEquals(serverId, chooser.serverId)
    }

    private class FakeApprover : PairApprover {
        val calls = mutableListOf<String>()

        override suspend fun lookup(code: String): PairLookup {
            calls += "lookup $code"
            return PairLookup(code, PairDevice("Living Room", "Android TV", "3.1.0"), remote = true)
        }

        override suspend fun approve(code: String, userId: Long): PairApproved {
            calls += "approve $code $userId"
            return PairApproved(ok = true, userId = if (userId == 0L) 1 else userId)
        }

        override suspend fun deny(code: String) {
            calls += "deny $code"
        }

        override suspend fun profiles(): List<Profile> = listOf(
            Profile(1, "Alex", "grad-1", hasPassword = true),
            Profile(2, "Kiddo", "grad-4"),
        )
    }

    /** A fake camera: a button that "reads" the given QR text. */
    private fun fakeScanner(text: String): @androidx.compose.runtime.Composable (onText: (String) -> Unit, modifier: Modifier) -> Unit =
        { onText, modifier ->
            Box(modifier.background(AntresolColors.FillSecondary), contentAlignment = Alignment.Center) {
                androidx.compose.material3.TextButton(onClick = { onText(text) }) { Text("camera") }
            }
        }

    @Test
    fun scanApproveForAnotherProfileAsAdmin() {
        val api = FakeApprover()
        val viewModel = PairTvViewModel(api, ownServerId = serverId, ownUserId = 1, isAdmin = true)
        compose.setContent {
            AntresolTheme {
                PairTvScreen(
                    viewModel,
                    ownName = "Alex",
                    ownAvatar = "grad-1",
                    onBack = {},
                    cameraAllowed = true,
                    scanner = fakeScanner("http://192.168.1.10:8420/pair?code=ABC234&sid=$serverId"),
                )
            }
        }
        compose.waitForIdle()
        shot("lan-phone-pair-scan")
        compose.onNodeWithText("camera").performClick()
        waitFor(hasText("Living Room"))
        compose.onNodeWithTag("pair-remote-warning").assertExists()
        compose.onNodeWithText("Android TV · v3.1.0").assertExists()
        shot("lan-phone-pair-confirm")
        compose.onNodeWithTag("pair-profile-2").performScrollTo().performClick()
        compose.onNodeWithTag("pair-approve").performScrollTo().performClick()
        waitFor(hasText("The TV is signing in."))
        shot("lan-phone-pair-done")
        assertEquals(listOf("lookup ABC234", "approve ABC234 2"), api.calls)
    }

    @Test
    fun ownProfileIsSentAsZeroAndOtherServersAreRefused() {
        val api = FakeApprover()
        val viewModel = PairTvViewModel(api, ownServerId = serverId, ownUserId = 7, isAdmin = false)
        compose.setContent {
            AntresolTheme {
                PairTvScreen(
                    viewModel,
                    ownName = "Kiddo",
                    ownAvatar = "grad-4",
                    onBack = {},
                    cameraAllowed = true,
                    scanner = fakeScanner("http://10.0.0.5:8420/pair?code=ABC234&sid=ffffffffffffffffffffffffffffffff"),
                )
            }
        }
        compose.onNodeWithText("camera").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("different Antresol server", substring = true).assertExists()
        assertTrue(api.calls.isEmpty())

        // A typed code works the same; a non-admin sees only their own profile.
        compose.onNodeWithTag("pair-code-field").performTextInput("abc-234")
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        waitFor(hasText("Living Room"))
        compose.onNodeWithTag("pair-profile-2").assertDoesNotExist()
        compose.onNodeWithTag("pair-profile-7").assertExists()
        compose.onNodeWithTag("pair-approve").performScrollTo().performClick()
        waitFor(hasText("The TV is signing in."))
        assertEquals(listOf("lookup ABC234", "approve ABC234 0"), api.calls)
    }

    @Test
    fun cameraPermissionRationaleAndDecline() {
        val api = FakeApprover()
        val viewModel = PairTvViewModel(api, ownServerId = serverId, ownUserId = 1, isAdmin = false)
        compose.setContent {
            AntresolTheme { PairTvScreen(viewModel, ownName = "Alex", ownAvatar = "grad-1", onBack = {}, cameraAllowed = false) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("only to read the TV’s QR code", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("pair-allow-camera").assertIsDisplayed()
        shot("lan-phone-pair-permission")
        compose.onNodeWithTag("pair-code-field").performTextInput("ABC1")
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.onNodeWithText("6 letters and digits", substring = true).assertExists()
        compose.onNodeWithTag("pair-code-field").performTextInput("234ABC") // the field now reads "ABC1234ABC": still invalid
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.onNodeWithText("6 letters and digits", substring = true).assertExists()
        viewModel.onCodeEntered("ABC 234")
        waitFor(hasText("Living Room"))
        compose.onNodeWithTag("pair-deny").performScrollTo().performClick()
        waitFor(hasText("Declined."))
        assertEquals(listOf("lookup ABC234", "deny ABC234"), api.calls)
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

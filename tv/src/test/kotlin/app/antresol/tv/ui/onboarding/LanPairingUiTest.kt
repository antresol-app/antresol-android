package app.antresol.tv.ui.onboarding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.discovery.DiscoveredServer
import app.antresol.core.discovery.DiscoveryState
import app.antresol.core.discovery.ServerFinder
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.Me
import app.antresol.core.model.PairPoll
import app.antresol.core.model.PairStart
import app.antresol.core.pairing.PairingApi
import app.antresol.core.pairing.QrCodes
import app.antresol.core.session.AuthState
import app.antresol.core.session.InMemorySessionStore
import app.antresol.core.session.SessionManager
import app.antresol.tv.AntresolTvTheme
import app.antresol.tv.ui.root.AntresolTvRoot
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
 * LAN discovery on the server screen and the QR sign-in screen, against a MockWebServer that
 * plays the Antresol server (health, setup, profiles, pair start/poll). Screenshots go to
 * `tv/build/screenshots/lan-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanPairingUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shots = File("build/screenshots").apply { mkdirs() }
    private lateinit var server: MockWebServer
    private val serverId = "0123456789abcdef0123456789abcdef"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/health" -> json("""{"ok":true,"serverId":"$serverId","version":"1.4.0"}""")
                "/api/setup" -> json("""{"needsSetup":false}""")
                "/api/profiles" -> json("""[{"id":1,"name":"Alex","avatar":"grad-1","hasPassword":true},{"id":2,"name":"Kiddo","avatar":"grad-4"}]""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val baseUrl get() = server.url("/").toString().trimEnd('/')

    private fun manager() = SessionManager(
        store = InMemorySessionStore(deviceId = "test-tv-device-0001"),
        deviceInfo = { DeviceInfo(it, "Гостиная", "Android TV", "1.0") },
        language = { "en" },
        scope = scope,
    )

    @Test
    fun foundServersAreListedAndTheFirstTakesFocus() {
        val session = manager()
        runBlocking { session.restore() }
        val state = MutableStateFlow(DiscoveryState(emptyList(), searching = true))
        val finder = ServerFinder { state }
        compose.setContent { AntresolTvTheme { AntresolTvRoot(session, finder) } }
        settle()
        compose.onNodeWithText("Looking for servers", substring = true).assertExists()
        shot("lan-tv-server-searching")

        state.value = DiscoveryState(emptyList(), searching = false)
        settle()
        compose.onNodeWithText("runs in Docker", substring = true).assertExists()
        shot("lan-tv-server-none-found")

        val other = DiscoveredServer("ffffffffffffffffffffffffffffffff", "Dacha", "1.3.2", "http://192.168.1.77:8420")
        val home = DiscoveredServer(serverId, "Home Media", "1.4.0", baseUrl)
        state.value = DiscoveryState(listOf(home, other), searching = false)
        settle()
        compose.onNodeWithText("ON YOUR NETWORK").assertExists()
        compose.onNodeWithText("Dacha").assertExists()
        compose.onNodeWithText("192.168.1.77:8420 · version 1.3.2").assertExists()
        compose.onNode(isFocused()).assert(hasText("Home Media"))
        shot("lan-tv-server-found")

        // Down twice reaches the field: the list is above it and the manual path stays.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(isFocused()).assert(hasText("Dacha"))
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Who’s watching?"))
        val chooser = session.state.value as AuthState.ChooseProfile
        assertEquals(baseUrl, chooser.serverUrl)
        assertEquals(serverId, chooser.serverId)
        // Connecting validated the address exactly like a typed one.
        val paths = List(server.requestCount) { server.takeRequest().path }
        assertTrue(paths.toString(), paths.containsAll(listOf("/api/health", "/api/setup")))
        compose.onNodeWithText("Sign In with Your Phone").assertExists()
        shot("lan-tv-profiles-with-phone")
    }

    /** A focus that arrived while the user was already moving around is not taken away. */
    @Test
    fun aServerFoundLaterDoesNotStealFocusFromTheUser() {
        val session = manager()
        runBlocking { session.restore() }
        val state = MutableStateFlow(DiscoveryState(emptyList(), searching = true))
        compose.setContent { AntresolTvTheme { AntresolTvRoot(session, ServerFinder { state }) } }
        settle()
        key(KeyEvent.KEYCODE_DPAD_DOWN) // field → Connect
        state.value = DiscoveryState(listOf(DiscoveredServer(serverId, "Home Media", "1.4.0", baseUrl)), searching = false)
        settle()
        compose.onNode(isFocused()).assert(hasText("Connect"))
    }

    private class FakePairing(var nextPoll: () -> PairPoll = { PairPoll.Pending(2) }) : PairingApi {
        var starts = 0
        val devices = mutableListOf<DeviceInfo>()

        override suspend fun start(device: DeviceInfo): PairStart {
            starts++
            devices += device
            return PairStart(
                code = if (starts == 1) "ABC234" else "XYZ789",
                secret = "secret",
                expiresAt = "",
                interval = 2,
                url = "http://192.168.1.10:8420/pair?code=ABC234&sid=0123456789abcdef0123456789abcdef",
                serverId = "0123456789abcdef0123456789abcdef",
            )
        }

        override suspend fun poll(secret: String): PairPoll = nextPoll()
    }

    @Test
    fun qrScreenShowsAScannableCodeCountsDownAndSignsIn() {
        val session = manager()
        runBlocking { session.connect(baseUrl) }
        val pairing = FakePairing()
        compose.setContent {
            AntresolTvTheme {
                val chooser = session.state.value as? AuthState.ChooseProfile ?: return@AntresolTvTheme
                PhoneSignInScreen(session, chooser, pairing, onBack = {}, now = SystemClock::uptimeMillis)
            }
        }
        waitFor(hasText("ABC 234"))
        compose.onNodeWithText("The code expires in 5:00").assertExists()
        compose.onNodeWithText("192.168.1.10:8420/pair", substring = true).assertExists()
        assertEquals("Гостиная", pairing.devices.single().name)
        assertEquals("test-tv-device-0001", pairing.devices.single().id)
        idle(61_000)
        compose.onNodeWithText("The code expires in 3:59").assertExists()
        val bitmap = shot("lan-tv-qr-sign-in")
        // The QR on screen decodes back to the pairing URL.
        assertEquals(
            "http://192.168.1.10:8420/pair?code=ABC234&sid=0123456789abcdef0123456789abcdef",
            decode(bitmap),
        )

        // The 5 minutes run out: one more poll, then a new code without a key press.
        idle(240_000)
        waitFor(hasText("XYZ 789"))
        assertEquals(2, pairing.starts)

        pairing.nextPoll = { PairPoll.Approved("tv-token", Me(id = 2, name = "Kiddo", isKid = true)) }
        idle(2_100)
        waitForState(session) { it is AuthState.SignedIn }
        val signedIn = session.state.value as AuthState.SignedIn
        assertEquals("tv-token", signedIn.session.token)
        assertEquals(2L, signedIn.session.userId)
    }

    @Test
    fun declinedOffersANewCodeAndBackLeaves() {
        val session = manager()
        runBlocking { session.connect(baseUrl) }
        val pairing = FakePairing { PairPoll.Denied("Sign-in declined") }
        var left by mutableStateOf(false)
        compose.setContent {
            AntresolTvTheme {
                val chooser = session.state.value as? AuthState.ChooseProfile ?: return@AntresolTvTheme
                if (!left) PhoneSignInScreen(session, chooser, pairing, onBack = { left = true }, now = SystemClock::uptimeMillis)
            }
        }
        waitFor(hasText("ABC 234"))
        idle(2_100)
        waitFor(hasText("New Code"))
        compose.onNodeWithText("Sign-in was declined on the phone.", substring = true).assertExists()
        compose.onNode(isFocused()).assert(hasText("New Code"))
        shot("lan-tv-qr-declined")

        pairing.nextPoll = { PairPoll.Pending(2) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("XYZ 789"))
        assertEquals(2, pairing.starts)

        key(KeyEvent.KEYCODE_BACK)
        assertTrue(left)
    }

    private fun decode(bitmap: Bitmap): String? {
        val tag = compose.onNodeWithTag("pair-qr").fetchSemanticsNode().boundsInRoot
        val left = tag.left.toInt()
        val top = tag.top.toInt()
        val w = tag.width.toInt()
        val h = tag.height.toInt()
        val luminance = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = bitmap.getPixel(left + x, top + y)
                luminance[y * w + x] = (((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000).toByte()
            }
        }
        return QrCodes.decode(luminance, w, w, h)
    }

    private fun idle(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 500)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            compose.mainClock.advanceTimeBy(step)
            left -= step
        }
        compose.waitForIdle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun waitForState(session: SessionManager, predicate: (AuthState) -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            predicate(session.state.value)
        }
    }

    private fun key(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun shot(name: String): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
}

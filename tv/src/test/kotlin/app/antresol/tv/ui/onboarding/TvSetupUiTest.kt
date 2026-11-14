package app.antresol.tv.ui.onboarding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
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
import app.antresol.core.pairing.PairingTicket
import app.antresol.core.pairing.QrCodes
import app.antresol.core.session.AuthState
import app.antresol.core.session.InMemorySessionStore
import app.antresol.core.session.SessionManager
import app.antresol.core.tvsetup.ScannedTvCode
import app.antresol.core.tvsetup.TvSetupAnswer
import app.antresol.core.tvsetup.TvSetupLink
import app.antresol.core.tvsetup.TvSetupSender
import app.antresol.tv.AntresolTvTheme
import app.antresol.tv.ui.root.AntresolTvRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * «TV setup by phone» on the TV: the first-run screen (QR of the setup URL, servers on this
 * network, typed address), its listener answering a phone's POST, the sign-in QR as the default
 * once a server is known, and the remote fallback. A MockWebServer plays the Antresol server.
 * Screenshots go to `$ANTRESOL_SHOTS_DIR` (default `tv/build/tv-setup-shots`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSetupUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shots = File(System.getenv("ANTRESOL_SHOTS_DIR") ?: "build/tv-setup-shots").apply { mkdirs() }
    private lateinit var server: MockWebServer
    private val serverId = "0123456789abcdef0123456789abcdef"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val starts = AtomicInteger()
    private val approved = AtomicBoolean(false)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/health" -> json("""{"ok":true,"serverId":"$serverId","version":"1.4.0"}""")
                "/api/setup" -> json("""{"needsSetup":false}""")
                "/api/profiles" -> json("""[{"id":1,"name":"Alex","avatar":"grad-1","hasPassword":true},{"id":2,"name":"Kiddo","avatar":"grad-4"}]""")
                "/api/auth/pair/start" -> {
                    val code = if (starts.incrementAndGet() == 1) "XH9U6Z" else "QWE789"
                    json("""{"code":"$code","secret":"secret-$code","expiresAt":"","interval":2,"url":"http://192.168.1.10:8420/pair?code=$code&sid=$serverId","serverId":"$serverId"}""")
                }
                "/api/auth/pair/poll" -> if (approved.get() && request.body.readUtf8().contains("secret-XH9U6Z")) {
                    json("""{"status":"approved","token":"tv-token","user":{"id":2,"name":"Kiddo","avatar":"grad-4","isKid":true}}""")
                } else {
                    json("""{"status":"pending","interval":2}""")
                }
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

    private val loopback: () -> InetAddress? = { InetAddress.getLoopbackAddress() }

    @Test
    fun firstRunShowsTheSetupQrAndTheRemoteFallbacks() {
        val session = manager()
        runBlocking { session.restore() }
        val discovery = MutableStateFlow(DiscoveryState(emptyList(), searching = true))
        compose.setContent { AntresolTvTheme { AntresolTvRoot(session, ServerFinder { discovery }, lanAddress = loopback) } }
        waitFor(hasText("Set Up with Your Phone"))
        waitForTag("setup-qr")
        compose.onNodeWithText("Scan the code with your phone’s camera or in the Antresol app", substring = true).assertExists()
        compose.onNodeWithText("Looking for servers", substring = true).assertExists()
        // The remote works at once: focus is on the first fallback control, not on the QR.
        compose.onNode(isFocused()).assert(hasText("Enter the Address with the Remote"))
        val bitmap = shot("tv-setup-first-run")
        // The QR on screen is the TV's setup URL with a fresh nonce.
        val link = (ScannedTvCode.parse(decode(bitmap, "setup-qr")!!, allowLoopback = true) as ScannedTvCode.Setup).link
        assertEquals("127.0.0.1", link.host)
        assertTrue(link.port > 0)

        // Servers found on the network sit above the typed-address button; the first takes focus.
        discovery.value = DiscoveryState(
            listOf(
                DiscoveredServer(serverId, "Home Media", "1.4.0", "http://192.168.1.10:8420"),
                DiscoveredServer("f".repeat(32), "Dacha", "1.3.2", "http://192.168.1.77:8420"),
            ),
            searching = false,
        )
        settle()
        compose.onNodeWithText("SERVERS ON THIS NETWORK").assertExists()
        compose.onNode(isFocused()).assert(hasText("Home Media"))
        shot("tv-setup-first-run-servers")

        // «Enter the Address with the Remote» → the typed address; Back returns here.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(isFocused()).assert(hasText("Enter the Address with the Remote"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Connect to your server"))
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Set Up with Your Phone"))
    }

    @Test
    @Config(qualifiers = "ru-w960dp-h540dp-land-television-xhdpi")
    fun firstRunInRussian() {
        val session = manager()
        runBlocking { session.restore() }
        val discovery = MutableStateFlow(DiscoveryState(listOf(DiscoveredServer(serverId, "Antresol", "1.4.0", "http://192.168.1.10:8420")), searching = false))
        compose.setContent { AntresolTvTheme { AntresolTvRoot(session, ServerFinder { discovery }, lanAddress = loopback) } }
        waitFor(hasText("Настройте с телефона"))
        waitForTag("setup-qr")
        compose.onNodeWithText("Отсканируйте код камерой телефона или в приложении Antresol", substring = true).assertExists()
        compose.onNodeWithText("СЕРВЕРЫ В ЭТОЙ СЕТИ").assertExists()
        compose.onNodeWithText("Ввести адрес пультом").assertExists()
        shot("tv-setup-first-run-ru")
    }

    @Test
    fun withoutALanAddressOnlyTheFallbacksAreShown() {
        val session = manager()
        runBlocking { session.restore() }
        compose.setContent {
            AntresolTvTheme { AntresolTvRoot(session, ServerFinder { MutableStateFlow(DiscoveryState(emptyList(), searching = false)) }, lanAddress = { null }) }
        }
        waitFor(hasText("isn’t connected to a home network", substring = true))
        compose.onNodeWithTag("setup-qr").assertDoesNotExist()
        compose.onNode(isFocused()).assert(hasText("Enter the Address with the Remote"))
        shot("tv-setup-no-network")
    }

    /**
     * The phone POSTs its server to the TV's listener: the TV checks it, starts pairing, answers
     * with the code, saves the server and shows its sign-in QR with that same code (no second
     * start); the phone's approval signs the TV in.
     */
    @Test
    fun aPhonePostSwitchesTheTvToItsSignInQrWithTheSameCode() {
        val session = manager()
        runBlocking { session.restore() }
        val ticket = PairingTicket()
        compose.setContent { AntresolTvTheme { AntresolTvRoot(session, ServerFinder.None, ticket, lanAddress = loopback) } }
        waitForTag("setup-qr")
        val qr = decode(shot("tv-setup-before-post"), "setup-qr")!!
        val link = TvSetupLink.parse(qr, allowLoopback = true)!!

        // The phone's request runs off the main thread; the TV's work happens on its listener.
        val answer = CoroutineScope(Dispatchers.IO).async { TvSetupSender().send(link, baseUrl, serverId) }
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            answer.isCompleted
        }
        val result: TvSetupAnswer = runBlocking { answer.await() }
        assertEquals(TvSetupAnswer("XH9U6Z", serverId, baseUrl, "Гостиная"), result)

        waitFor(hasText("XH9 U6Z"))
        assertEquals(AuthState.ChooseProfile(baseUrl, serverId), session.state.value)
        compose.onNodeWithText("Sign In with Your Phone").assertExists()
        compose.onNode(isFocused()).assert(hasText("Sign In with the Remote"))
        assertEquals(1, starts.get())
        shot("tv-sign-in-qr-after-setup")

        approved.set(true)
        idle(2_100)
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            session.state.value is AuthState.SignedIn
        }
        assertEquals(1, starts.get())
        assertEquals("tv-token", (session.state.value as AuthState.SignedIn).session.token)
    }

    @Test
    fun aKnownServerOpensOnTheSignInQrAndTheRemoteIsTheFallback() {
        val session = manager()
        runBlocking { session.connect(baseUrl) }
        compose.setContent { AntresolTvTheme { AntresolTvRoot(session, lanAddress = { null }) } }
        waitFor(hasText("XH9 U6Z"))
        compose.onNodeWithText("The code expires in", substring = true).assertExists()
        compose.onNodeWithText("Connected to", substring = true).assertExists()
        compose.onNode(isFocused()).assert(hasText("Sign In with the Remote"))
        val bitmap = shot("tv-sign-in-qr-default")
        assertEquals("http://192.168.1.10:8420/pair?code=XH9U6Z&sid=$serverId", decode(bitmap, "pair-qr"))

        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Who’s watching?"))
        shot("tv-sign-in-remote-picker")
        // Back in the picker returns to the QR.
        key(KeyEvent.KEYCODE_BACK)
        waitFor(hasText("Sign In with Your Phone"))

        // «Change Server» goes back to the phone setup screen.
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNode(isFocused()).assert(hasText("Change Server"))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(hasText("Set Up with Your Phone"))
    }

    @Test
    @Config(qualifiers = "ru-w960dp-h540dp-land-television-xhdpi")
    fun signInQrInRussian() {
        val session = manager()
        runBlocking { session.connect(baseUrl) }
        compose.setContent { AntresolTvTheme { AntresolTvRoot(session, lanAddress = { null }) } }
        waitFor(hasText("XH9 U6Z"))
        compose.onNodeWithText("Войти с телефона").assertExists()
        compose.onNodeWithText("Войти пультом").assertExists()
        shot("tv-sign-in-qr-default-ru")
    }

    private fun decode(bitmap: Bitmap, tag: String): String? {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val left = bounds.left.toInt()
        val top = bounds.top.toInt()
        val w = bounds.width.toInt()
        val h = bounds.height.toInt()
        val luminance = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = bitmap.getPixel(left + x, top + y)
                luminance[y * w + x] = (((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000).toByte()
            }
        }
        return QrCodes.decode(luminance, w, w, h).also { assertNotNull("QR not readable", it) }
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

    private fun waitForTag(tag: String) = waitFor(hasTestTag(tag))

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

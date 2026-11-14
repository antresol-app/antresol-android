package app.antresol.core.pairing

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.Me
import app.antresol.core.model.PairPoll
import app.antresol.core.model.PairStart
import app.antresol.core.session.AuthState
import app.antresol.core.session.InMemorySessionStore
import app.antresol.core.session.SessionManager
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PairingTest {
    private val device = DeviceInfo("test-device-0001", "Гостиная", "Android TV", "3.1.0")
    private val kid = Me(id = 3, name = "Kiddo", isKid = true)

    /** A scripted server: each `start` hands out the next code; `poll` answers from [polls] in order. */
    private class FakeApi(val polls: ArrayDeque<() -> PairPoll>, val startFailures: ArrayDeque<Exception> = ArrayDeque()) : PairingApi {
        var starts = 0
        val pollTimes = mutableListOf<Long>()
        var clock: () -> Long = { 0 }

        override suspend fun start(device: DeviceInfo): PairStart {
            startFailures.removeFirstOrNull()?.let { throw it }
            starts++
            return PairStart(
                code = if (starts == 1) "abc234" else "XYZ789",
                secret = "secret-$starts",
                expiresAt = "2026-11-14T12:05:00Z",
                interval = 2,
                url = "http://192.168.1.10:8420/pair?code=ABC234&sid=0123",
                serverId = "0123",
            )
        }

        override suspend fun poll(secret: String): PairPoll {
            pollTimes += clock()
            return (polls.removeFirstOrNull() ?: { PairPoll.Pending(2) })()
        }
    }

    private fun TestScope.flow(api: FakeApi): PairingFlow {
        api.clock = { currentTime }
        return PairingFlow(api, { device }, now = { currentTime })
    }

    @Test
    fun pendingThenApproved() = runTest {
        val api = FakeApi(ArrayDeque(listOf({ PairPoll.Pending(2) }, { PairPoll.Pending(2) }, { PairPoll.Approved("tok", kid) })))
        val states = flow(api).states().toList()
        assertEquals(PairingState.Starting, states[0])
        val pending = states[1] as PairingState.Pending
        assertEquals("ABC234", pending.code)
        assertEquals("http://192.168.1.10:8420/pair?code=ABC234&sid=0123", pending.url)
        // The device clock is not the server's: the contract's 5 minutes count from receipt.
        assertEquals(PairingFlow.DEFAULT_TTL_MS, pending.deadlineMs)
        assertEquals(300L, pending.secondsLeft(0))
        assertEquals(PairingState.Approved("tok", kid), states.last())
        assertEquals(listOf(2_000L, 4_000L, 6_000L), api.pollTimes)
    }

    @Test
    fun deniedEndsTheFlow() = runTest {
        val api = FakeApi(ArrayDeque(listOf({ PairPoll.Denied("Вход отклонён") })))
        val states = flow(api).states().toList()
        assertEquals(PairingState.Denied("Вход отклонён"), states.last())
        assertEquals(1, api.starts)
    }

    @Test
    fun expiredAnswerStartsANewCode() = runTest {
        val api = FakeApi(ArrayDeque(listOf({ PairPoll.Expired("Код устарел") }, { PairPoll.Approved("tok", kid) })))
        val states = flow(api).states().toList()
        val codes = states.filterIsInstance<PairingState.Pending>().map { it.code }
        assertEquals(listOf("ABC234", "XYZ789"), codes)
        assertEquals(2, api.starts)
        assertTrue(states.last() is PairingState.Approved)
    }

    @Test
    fun countdownEndPollsOnceMoreThenRenews() = runTest {
        // Pending for the whole 5 minutes, then approved on the second code.
        var renewed = false
        val api = object : PairingApi {
            var starts = 0
            override suspend fun start(device: DeviceInfo): PairStart {
                starts++
                return PairStart("ABC23$starts".replace("1", "4").replace("2", "5"), "s$starts", "bad date", 2, "http://h/pair?code=x")
            }

            override suspend fun poll(secret: String): PairPoll =
                if (secret == "s2") PairPoll.Approved("tok", kid).also { renewed = true } else PairPoll.Pending(null)
        }
        val states = PairingFlow(api, { device }, now = { currentTime }).states().toList()
        assertTrue(renewed)
        assertEquals(2, api.starts)
        // The first code lived its full 5 minutes before the new start.
        val second = states.filterIsInstance<PairingState.Pending>()[1]
        assertEquals(PairingFlow.DEFAULT_TTL_MS * 2, second.deadlineMs)
    }

    @Test
    fun networkErrorsKeepPollingAndThrottleWaits() = runTest {
        val api = FakeApi(
            ArrayDeque(
                listOf(
                    { throw IOException("wifi dropped") },
                    { PairPoll.Throttled(30) },
                    { PairPoll.Approved("tok", kid) },
                ),
            ),
        )
        val states = flow(api).states().toList()
        assertTrue(states.last() is PairingState.Approved)
        // 2 s (error), 4 s (throttled → wait 30 s), then 2 s interval: 36 s.
        assertEquals(listOf(2_000L, 4_000L, 36_000L), api.pollTimes)
    }

    @Test
    fun failedStartRetriesAfterRetryAfter() = runTest {
        val api = FakeApi(
            ArrayDeque(listOf({ PairPoll.Approved("tok", kid) })),
            startFailures = ArrayDeque(listOf(ApiException(429, "too many", retryAfterSec = 30), IOException("offline"))),
        )
        val states = flow(api).states().toList()
        val retries = states.filterIsInstance<PairingState.Retrying>()
        assertEquals(listOf(30_000L, 35_000L), retries.map { it.retryAtMs })
        assertTrue(states.last() is PairingState.Approved)
    }

    @Test
    fun codesAndLinks() {
        assertEquals("ABC234", PairCode.normalize(" abc-234 "))
        assertEquals("ABC234", PairCode.normalize("ABC 234"))
        assertNull(PairCode.normalize("ABC23")) // too short
        assertNull(PairCode.normalize("ABC10O")) // 1, 0 and O are not in the alphabet
        assertEquals("ABC 234", PairCode.display("ABC234"))

        val link = PairLink.parse("http://192.168.1.10:8420/pair?code=ABC234&sid=0123456789ABCDEF0123456789abcdef")!!
        assertEquals("ABC234", link.code)
        assertEquals("0123456789abcdef0123456789abcdef", link.serverId)
        assertTrue(link.belongsTo("0123456789abcdef0123456789abcdef"))
        assertFalse(link.belongsTo("ffffffffffffffffffffffffffffffff"))
        assertEquals(PairLink("ABC234", null), PairLink.parse("https://media.example.org/pair/?code=abc234"))
        assertEquals(PairLink("ABC234", null), PairLink.parse("abc 234"))
        assertTrue(PairLink.parse("abc 234")!!.belongsTo("anything"))
        assertNull(PairLink.parse("https://media.example.org/other?code=ABC234"))
        assertNull(PairLink.parse("https://media.example.org/pair"))
        assertNull(PairLink.parse("WIFI:S:home;T:WPA;P:secret;;"))
    }

    @Test
    fun qrRoundTrip() {
        val url = "http://192.168.1.10:8420/pair?code=ABC234&sid=0123456789abcdef0123456789abcdef"
        val matrix = QrCodes.encode(url)
        assertTrue(matrix.size in 21..60)
        // Draw it like a screen would (4 px per module, 4-module white quiet zone) and read it back.
        val scale = 4
        val quiet = 4
        val side = (matrix.size + quiet * 2) * scale
        val pixels = ByteArray(side * side) { i ->
            val x = (i % side) / scale - quiet
            val y = (i / side) / scale - quiet
            val dark = x in 0 until matrix.size && y in 0 until matrix.size && matrix[x, y]
            if (dark) 0 else 255.toByte()
        }
        assertEquals(url, QrCodes.decode(pixels, side, side, side))
        assertNull(QrCodes.decode(ByteArray(64 * 64) { 127 }, 64, 64, 64))
    }

    @Test
    fun clientCallsMatchTheContract() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val client = AntresolClient(server.url("/").toString(), language = { "ru" })
            server.enqueue(
                MockResponse().setBody(
                    """{"code":"ABC234","secret":"s3cret","expiresAt":"2026-11-14T12:05:00Z","interval":2,
                       "url":"http://192.168.1.10:8420/pair?code=ABC234&sid=0123","serverId":"0123"}""",
                ),
            )
            val start = client.pairStart(device)
            assertEquals("s3cret", start.secret)
            val startRequest = server.takeRequest()
            assertEquals("/api/auth/pair/start", startRequest.path)
            assertTrue(startRequest.getHeader("Content-Type")!!.startsWith("application/json"))
            assertEquals(
                """{"device":{"id":"test-device-0001","name":"Гостиная","model":"Android TV","version":"3.1.0"}}""",
                startRequest.body.readUtf8(),
            )

            server.enqueue(MockResponse().setBody("""{"status":"pending","expiresAt":"2026-11-14T12:05:00Z","interval":3}"""))
            assertEquals(PairPoll.Pending(3), client.pairPoll("s3cret"))
            val poll = server.takeRequest()
            assertEquals("/api/auth/pair/poll", poll.path)
            assertEquals("""{"secret":"s3cret"}""", poll.body.readUtf8())

            server.enqueue(MockResponse().setBody("""{"status":"approved","token":"tok","user":{"id":3,"name":"Kiddo","isKid":true}}"""))
            assertEquals(PairPoll.Approved("tok", Me(id = 3, name = "Kiddo", isKid = true)), client.pairPoll("s3cret"))
            server.enqueue(MockResponse().setResponseCode(410).setBody("""{"status":"denied","error":"Вход отклонён"}"""))
            assertEquals(PairPoll.Denied("Вход отклонён"), client.pairPoll("s3cret"))
            server.enqueue(MockResponse().setResponseCode(410).setBody("""{"status":"expired","error":"Код устарел"}"""))
            assertEquals(PairPoll.Expired("Код устарел"), client.pairPoll("s3cret"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60").setBody("""{"error":"slow down"}"""))
            assertEquals(PairPoll.Throttled(60), client.pairPoll("s3cret"))
            repeat(4) { server.takeRequest() }

            client.token = "phone-token"
            server.enqueue(
                MockResponse().setBody(
                    """{"code":"ABC234","device":{"name":"Гостиная","model":"Android TV","version":"3.1.0"},"remote":true,
                       "createdAt":"2026-11-14T12:00:00Z","expiresAt":"2026-11-14T12:05:00Z"}""",
                ),
            )
            val lookup = client.pairLookup("abc-234")
            assertEquals("Гостиная", lookup.device.name)
            assertTrue(lookup.remote)
            val lookupRequest = server.takeRequest()
            assertEquals("Bearer phone-token", lookupRequest.getHeader("Authorization"))
            assertEquals("""{"code":"abc-234"}""", lookupRequest.body.readUtf8())

            server.enqueue(MockResponse().setBody("""{"ok":true,"userId":3}"""))
            assertEquals(3L, client.pairApprove("ABC234", 3).userId)
            assertEquals("""{"code":"ABC234","userId":3}""", server.takeRequest().body.readUtf8())

            server.enqueue(MockResponse().setResponseCode(204))
            client.pairDeny("ABC234")
            val deny = server.takeRequest()
            assertEquals("/api/auth/pair/deny", deny.path)
            assertEquals("""{"code":"ABC234"}""", deny.body.readUtf8())

            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Код не найден или устарел"}"""))
            val error = runCatching { client.pairLookup("ZZZ999") }.exceptionOrNull() as ApiException
            assertEquals(404, error.status)
            assertEquals("Код не найден или устарел", error.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun approvedTokenIsStoredAsANormalSignIn() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val store = InMemorySessionStore(deviceId = "test-device-0001")
            val manager = SessionManager(store, { DeviceInfo(it, "TV", "Test", "0.1") }, { "en" }, this)
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""{"ok":true,"serverId":"srv1","version":"1.4.0"}"""))
            server.enqueue(MockResponse().setBody("""{"needsSetup":false}"""))
            manager.connect(base)
            assertEquals("test-device-0001", manager.deviceInfo().id)
            val wrongServer = runCatching { manager.signInWithPairing("tok", kid, serverId = "other") }
            assertTrue(wrongServer.isFailure)
            manager.signInWithPairing("tok", kid, serverId = "srv1")
            val state = manager.state.value as AuthState.SignedIn
            assertEquals("tok", state.session.token)
            assertEquals(3L, state.session.userId)
            assertEquals("tok", manager.client?.token)
            assertEquals("tok", store.load().tokenFor("srv1", 3)?.token)
        } finally {
            server.shutdown()
        }
    }
}

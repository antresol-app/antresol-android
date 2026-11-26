package app.antresol.core.tvsetup

import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.Me
import app.antresol.core.model.PairPoll
import app.antresol.core.model.PairStart
import app.antresol.core.pairing.PairLink
import app.antresol.core.pairing.PairingApi
import app.antresol.core.pairing.PairingFlow
import app.antresol.core.pairing.PairingTicket
import app.antresol.core.pairing.PairingState
import app.antresol.core.pairing.StartedPairing
import app.antresol.core.session.AuthState
import app.antresol.core.session.InMemorySessionStore
import app.antresol.core.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class TvSetupTest {
    private val serverId = "0123456789abcdef0123456789abcdef"
    private val nonce = "AAAAAAAAAAAAAAAAAAAAAA"

    // region Links

    @Test
    fun parsesTheTvSetupLink() {
        val link = TvSetupLink.parse("http://192.168.1.50:41234/setup?n=$nonce&v=1")!!
        assertEquals(TvSetupLink("192.168.1.50", 41234, nonce), link)
        assertEquals("http://192.168.1.50:41234/setup", link.setupUrl.toString())
        assertEquals("http://192.168.1.50:41234/setup?n=$nonce&v=1", link.toString())
        // Link-local and the other private ranges, no v (version 1 assumed), surrounding spaces.
        assertNotNull(TvSetupLink.parse(" http://169.254.10.2:5000/setup?n=$nonce "))
        assertNotNull(TvSetupLink.parse("http://10.0.0.7:5000/setup?n=$nonce&v=1"))
        assertNotNull(TvSetupLink.parse("http://172.20.1.1:5000/setup/?n=$nonce&v=1"))
    }

    @Test
    fun refusesOtherLinks() {
        listOf(
            "https://192.168.1.50:41234/setup?n=$nonce&v=1", // the TV listens on plain http only
            "http://8.8.8.8:41234/setup?n=$nonce&v=1", // not the home network
            "http://tv.local:41234/setup?n=$nonce&v=1", // no names: no lookup of a scanned host
            "http://192.168.1.50:41234/setup?v=1", // no nonce
            "http://192.168.1.50:41234/setup?n=short&v=1",
            "http://192.168.1.50:41234/setup?n=$nonce&v=2", // a newer protocol
            "http://192.168.1.50:41234/pairing?n=$nonce&v=1",
            "http://127.0.0.1:41234/setup?n=$nonce&v=1",
            "hello",
        ).forEach { assertNull(it, TvSetupLink.parse(it)) }
        assertNotNull(TvSetupLink.parse("http://127.0.0.1:41234/setup?n=$nonce&v=1", allowLoopback = true))
    }

    @Test
    fun scannerTellsTheTwoKindsApart() {
        val setup = ScannedTvCode.parse("http://192.168.1.50:41234/setup?n=$nonce&v=1")
        assertTrue(setup is ScannedTvCode.Setup)
        val signIn = ScannedTvCode.parse("http://192.168.1.10:8420/pair?code=ABC234&sid=$serverId")
        assertEquals(ScannedTvCode.SignIn(PairLink("ABC234", serverId)), signIn)
        assertEquals(ScannedTvCode.SignIn(PairLink("ABC234", null)), ScannedTvCode.parse("abc-234"))
        assertNull(ScannedTvCode.parse("https://example.org/"))
    }

    // endregion

    // region Nonces

    @Test
    fun nonceIsRandomSingleUseAndExpires() {
        var clock = 0L
        val nonces = SetupNonces(now = { clock })
        val first = nonces.issue()
        assertEquals(22, first.length)
        assertTrue(SetupNonces.looksLikeNonce(first))
        assertTrue(nonces.isValid(first))
        assertFalse(nonces.isValid("x$first"))
        assertFalse(nonces.isValid(null))

        // One POST at a time; a failed one gives it back.
        assertTrue(nonces.claim(first))
        assertFalse(nonces.isValid(first))
        assertFalse(nonces.claim(first))
        nonces.release(first)
        assertTrue(nonces.claim(first))
        nonces.consume(first)
        assertFalse(nonces.isValid(first))
        nonces.release(first)
        assertFalse(nonces.claim(first))

        // A new one replaces the old; 10 minutes of life.
        val second = nonces.issue()
        assertTrue(second != first)
        assertEquals(SetupNonces.LIFETIME_MS, nonces.expiresAt())
        clock = SetupNonces.LIFETIME_MS - 1
        assertTrue(nonces.isValid(second))
        clock = SetupNonces.LIFETIME_MS
        assertFalse(nonces.isValid(second))
        assertFalse(nonces.claim(second))
    }

    // endregion

    // region Request validation and the JSON contract

    @Test
    fun parsesJsonAndFormBodies() {
        val json = TvSetupProtocol.parseRequest(
            "application/json; charset=utf-8",
            """{"n":"$nonce","server":" https://media.example ","serverId":"${serverId.uppercase()}","extra":1}""".toByteArray(),
        )
        assertEquals(ParsedSetupRequest(BodyKind.Json, nonce, "https://media.example", serverId), json)
        val form = TvSetupProtocol.parseRequest("application/x-www-form-urlencoded", "n=$nonce&server=192.168.1.10%3A8420".toByteArray())
        assertEquals(ParsedSetupRequest(BodyKind.Form, nonce, "192.168.1.10:8420", null), form)
        val plus = TvSetupProtocol.parseRequest("application/x-www-form-urlencoded", "n=$nonce&server=my+server".toByteArray())
        assertEquals("my server", plus.server)
    }

    @Test
    fun refusesBadBodies() {
        val cases = listOf(
            "text/plain" to "n=$nonce&server=x",
            null to "n=$nonce&server=x",
            "application/json" to "not json",
            "application/json" to """["n"]""",
            "application/json" to """{"n":"$nonce"}""",
            "application/json" to """{"n":"$nonce","server":"   "}""",
            "application/json" to """{"n":"bad","server":"x"}""",
            "application/json" to """{"n":"$nonce","server":"x","serverId":"nothex"}""",
            "application/json" to """{"n":"$nonce","server":5}""",
            "application/json" to """{"n":"$nonce","server":"${"a".repeat(600)}"}""",
            "application/x-www-form-urlencoded" to "n=$nonce&server=%zz",
            "application/json" to """{"n":"$nonce","server":"${"a".repeat(5000)}"}""",
        )
        for ((type, body) in cases) {
            try {
                TvSetupProtocol.parseRequest(type, body.toByteArray())
                fail("accepted $type $body")
            } catch (e: TvSetupException) {
                assertEquals(TvSetupError.BadRequest, e.error)
            }
        }
    }

    @Test
    fun jsonContract() {
        assertEquals(
            """{"code":"ABC234","sid":"$serverId","server":"http://192.168.1.10:8420","device":"Гостиная"}""",
            TvSetupProtocol.answerJson(TvSetupAnswer("ABC234", serverId, "http://192.168.1.10:8420", "Гостиная")),
        )
        assertEquals("""{"error":"expired_nonce"}""", TvSetupProtocol.errorJson(TvSetupError.ExpiredNonce))
        assertEquals(
            listOf(400 to "bad_request", 404 to "expired_nonce", 409 to "server_mismatch", 422 to "not_antresol", 429 to "rate_limited", 502 to "unreachable"),
            TvSetupError.entries.map { it.status to it.code },
        )
        // The phone's request, as the TV reads it.
        assertEquals(
            """{"n":"$nonce","server":"https://media.example","serverId":"$serverId"}""",
            TvSetupProtocol.json.encodeToString(TvSetupRequest.serializer(), TvSetupRequest(nonce, "https://media.example", serverId)),
        )
    }

    @Test
    fun pageLanguage() {
        assertEquals(TvSetupPage.Lang.Ru, TvSetupPage.language("ru-RU,ru;q=0.9,en;q=0.8"))
        assertEquals(TvSetupPage.Lang.En, TvSetupPage.language("en-GB,ru;q=0.5"))
        assertEquals(TvSetupPage.Lang.Ru, TvSetupPage.language("de;q=1,ru;q=0.8,en;q=0.5"))
        assertEquals(TvSetupPage.Lang.En, TvSetupPage.language(null))
        assertTrue(TvSetupPage.form(TvSetupPage.Lang.Ru, nonce, value = "\"><script>").contains("&quot;&gt;&lt;script&gt;"))
    }

    // endregion

    // region The listener on loopback

    private val http = OkHttpClient.Builder().followRedirects(false).build()
    private val listeners = mutableListOf<TvSetupListener>()

    @After
    fun stopListeners() = listeners.forEach(TvSetupListener::stop)

    private fun listen(clock: () -> Long = System::currentTimeMillis, handler: TvSetupHandler): Pair<TvSetupListener, TvSetupLink> {
        val listener = TvSetupListener(handler, now = clock)
        listeners += listener
        return listener to listener.start(InetAddress.getLoopbackAddress())
    }

    private fun get(url: String, lang: String = "en") =
        http.newCall(Request.Builder().url(url).header("Accept-Language", lang).build()).execute().use { it.code to it.body!!.string() }

    private fun post(link: TvSetupLink, type: String, body: String, lang: String = "en") = http.newCall(
        Request.Builder().url(link.setupUrl).header("Accept-Language", lang).post(body.toRequestBody(type.toMediaType())).build(),
    ).execute().use { Triple(it.code, it.body!!.string(), it.header("Location")) }

    private fun okHandler(commits: MutableList<String> = CopyOnWriteArrayList()) = TvSetupHandler { server, sid ->
        TvSetupAccepted(TvSetupAnswer("ABC234", sid ?: serverId, "http://${server.removePrefix("http://")}", "Гостиная")) { commits += server }
    }

    @Test
    fun getShowsTheFormOnlyForTheCurrentNonce() {
        val (_, link) = listen(handler = okHandler())
        val (status, page) = get(link.toString(), "ru-RU,ru;q=0.9")
        assertEquals(200, status)
        assertTrue(page.contains("Адрес сервера Antresol"))
        assertTrue(page.contains("""name="n" value="${link.nonce}""""))
        assertTrue(page.contains("placeholder=\"192.168.1.10:8420\""))
        assertTrue(page.contains("Продолжить"))

        val (expired, text) = get("http://${link.host}:${link.port}/setup?n=${"B".repeat(22)}&v=1", "ru")
        assertEquals(404, expired)
        assertTrue(text.contains("Код устарел — откройте настройку на телевизоре заново"))
        assertEquals(404, get("http://${link.host}:${link.port}/favicon.ico").first)
    }

    @Test
    fun jsonPostAnswersTheCodeCommitsAndCloses() {
        val commits = CopyOnWriteArrayList<String>()
        val (listener, link) = listen(handler = okHandler(commits))
        var finished = false
        listener.onFinished = { finished = true }
        val (status, body) = post(link, "application/json", """{"n":"${link.nonce}","server":"192.168.1.10:8420","serverId":"$serverId"}""")
        assertEquals(200, status)
        val answer = TvSetupProtocol.json.decodeFromString(TvSetupAnswer.serializer(), body)
        assertEquals(TvSetupAnswer("ABC234", serverId, "http://192.168.1.10:8420", "Гостиная"), answer)
        waitUntil { finished }
        assertEquals(listOf("192.168.1.10:8420"), commits)
        assertTrue(listener.stopped)
        // Setup ended: the port is closed.
        assertTrue(runCatching { get(link.toString()) }.isFailure)
    }

    /**
     * On Linux closing a listening socket does not wake a thread blocked in accept() at once, and a connection made in
     * that gap is still handed to it. A stopped listener must not answer it (what made the test above fail on CI).
     */
    @Test
    fun aConnectionMadeRightAfterStopIsNotAnswered() {
        repeat(40) { round ->
            val (listener, link) = listen(handler = okHandler())
            Thread.sleep(5) // the accept loop is blocked in accept()
            listener.stop()
            val firstByte = runCatching {
                Socket(link.host, link.port).use { socket ->
                    socket.soTimeout = 2_000
                    socket.getOutputStream().write("GET /setup?n=${link.nonce}&v=1 HTTP/1.1\r\nHost: tv\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getInputStream().read()
                }
            }
            assertTrue("round $round: the stopped listener answered", firstByte.isFailure || firstByte.getOrNull() == -1)
        }
    }

    @Test
    fun failedPostKeepsTheNonceAndASecondPostOfAUsedNonceIsExpired() {
        var fail = true
        val (_, link) = listen(handler = TvSetupHandler { server, _ ->
            if (fail) throw TvSetupException(TvSetupError.NotAntresol)
            TvSetupAccepted(TvSetupAnswer("ABC234", serverId, server, "TV"))
        })
        val json = """{"n":"${link.nonce}","server":"http://192.168.1.10:8420"}"""
        val (s1, b1) = post(link, "application/json", json)
        assertEquals(422, s1)
        assertEquals("""{"error":"not_antresol"}""", b1)
        fail = false
        assertEquals(200, post(link, "application/json", json).first)
    }

    @Test
    fun wrongNonceIsExpired() {
        val (_, link) = listen(handler = okHandler())
        val (status, body) = post(link, "application/json", """{"n":"${"C".repeat(22)}","server":"x"}""")
        assertEquals(404, status)
        assertEquals("""{"error":"expired_nonce"}""", body)
        val (bad, badBody) = post(link, "application/json", """{"n":"${link.nonce}"}""")
        assertEquals(400, bad)
        assertEquals("""{"error":"bad_request"}""", badBody)
    }

    @Test
    fun formPostRedirectsToTheServersApprovalPage() {
        val (_, link) = listen(handler = okHandler())
        val (status, _, location) = post(link, "application/x-www-form-urlencoded", "n=${link.nonce}&server=192.168.1.10%3A8420")
        assertEquals(303, status)
        assertEquals("http://192.168.1.10:8420/pair?code=ABC234&sid=$serverId", location)
    }

    @Test
    fun formErrorsShowTheFormAgain() {
        val (_, link) = listen(handler = TvSetupHandler { _, _ -> throw TvSetupException(TvSetupError.Unreachable) })
        val (status, page) = post(link, "application/x-www-form-urlencoded", "n=${link.nonce}&server=10.0.0.9", "ru").let { it.first to it.second }
        assertEquals(502, status)
        assertTrue(page.contains("Телевизор не может связаться с этим адресом"))
        assertTrue(page.contains("""value="10.0.0.9""""))
        // An empty field: the same form with "not an address".
        val (empty, emptyPage) = post(link, "application/x-www-form-urlencoded", "n=${link.nonce}&server=", "en").let { it.first to it.second }
        assertEquals(400, empty)
        assertTrue(emptyPage.contains("doesn’t look like a server address"))
    }

    @Test
    fun atMostTenRequestsAMinute() {
        var clock = 1_000_000L
        val (_, link) = listen(clock = { clock }, handler = okHandler())
        repeat(10) { assertEquals(200, get(link.toString()).first) }
        assertEquals(429, get(link.toString()).first)
        val (status, body) = post(link, "application/json", """{"n":"${link.nonce}","server":"x"}""")
        assertEquals(429, status)
        assertEquals("""{"error":"rate_limited"}""", body)
        clock += 60_000
        assertEquals(200, get(link.toString()).first)
    }

    @Test
    fun bodiesOver4KbAreRefused() {
        val (_, link) = listen(handler = okHandler())
        val big = """{"n":"${link.nonce}","server":"${"a".repeat(5000)}"}"""
        assertEquals(400, post(link, "application/json", big).first)
    }

    @Test
    fun renewReplacesTheNonce() {
        val (listener, link) = listen(handler = okHandler())
        val renewed = listener.renew()
        assertEquals(link.port, renewed.port)
        assertEquals(404, get(link.toString()).first)
        assertEquals(200, get(renewed.toString()).first)
    }

    @Test
    fun privateAddresses() {
        assertTrue(LocalAddresses.isPrivate(InetAddress.getByName("192.168.0.2")))
        assertTrue(LocalAddresses.isPrivate(InetAddress.getByName("10.1.2.3")))
        assertTrue(LocalAddresses.isPrivate(InetAddress.getByName("172.31.0.1")))
        assertTrue(LocalAddresses.isPrivate(InetAddress.getByName("169.254.3.3")))
        assertFalse(LocalAddresses.isPrivate(InetAddress.getByName("172.32.0.1")))
        assertFalse(LocalAddresses.isPrivate(InetAddress.getByName("100.64.0.1")))
        assertFalse(LocalAddresses.isPrivate(InetAddress.getByName("8.8.8.8")))
        assertFalse(LocalAddresses.isPrivate(InetAddress.getByName("127.0.0.1"), allowLoopback = false))
        assertTrue(LocalAddresses.isIpv4Literal("192.168.1.1"))
        assertFalse(LocalAddresses.isIpv4Literal("192.168.1.256"))
        assertFalse(LocalAddresses.isIpv4Literal("tv.local"))
    }

    // endregion

    // region The TV's handler and the phone's sender

    private fun mockServer(healthId: String = serverId, health: Int = 200, pairStart: Int = 200): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/health" -> if (health == 200) json("""{"ok":true,"serverId":"$healthId","version":"1.4.0"}""") else MockResponse().setResponseCode(health)
                "/api/setup" -> json("""{"needsSetup":false}""")
                "/api/auth/pair/start" -> if (pairStart == 200) {
                    json("""{"code":"ABC234","secret":"s3cret","expiresAt":"","interval":2,"url":"${url("/")}pair?code=ABC234&sid=$healthId","serverId":"$healthId"}""")
                } else {
                    json("""{"error":"busy"}""").setResponseCode(pairStart).setHeader("Retry-After", "30")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        start()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun tvSession() = SessionManager(
        store = InMemorySessionStore(deviceId = "test-tv-device-0001"),
        deviceInfo = { DeviceInfo(it, "Гостиная", "Android TV", "1.0") },
        language = { "en" },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun coordinatorChecksTheServerStartsPairingAndCommitsAfterTheAnswer() = runBlocking {
        val server = mockServer()
        try {
            val session = tvSession()
            session.restore()
            val ticket = PairingTicket()
            val base = server.url("/").toString().trimEnd('/')
            val accepted = TvSetupCoordinator(session, ticket, now = { 42L }).setUp(base, serverId)
            assertEquals(TvSetupAnswer("ABC234", serverId, base, "Гостиная"), accepted.answer)
            // Nothing changes on the TV until the answer is out.
            assertEquals(AuthState.NoServer, session.state.value)
            accepted.commit()
            assertEquals(AuthState.ChooseProfile(base, serverId), session.state.value)
            val started = ticket.take(serverId)!!
            assertEquals("s3cret", started.start.secret)
            assertEquals(42L, started.receivedAtMs)
            assertNull(ticket.take(serverId))
            val start = List(server.requestCount) { server.takeRequest() }.single { it.path == "/api/auth/pair/start" }
            assertTrue(start.body.readUtf8().contains("\"id\":\"test-tv-device-0001\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun coordinatorErrors() = runBlocking {
        suspend fun errorOf(server: MockWebServer?, address: String? = null, sid: String? = serverId): TvSetupError {
            val session = tvSession()
            session.restore()
            return try {
                TvSetupCoordinator(session, PairingTicket()).setUp(address ?: server!!.url("/").toString(), sid)
                error("no error")
            } catch (e: TvSetupException) {
                assertEquals(AuthState.NoServer, session.state.value)
                e.error
            } finally {
                server?.shutdown()
            }
        }
        assertEquals(TvSetupError.ServerMismatch, errorOf(mockServer(healthId = "f".repeat(32))))
        assertEquals(TvSetupError.NotAntresol, errorOf(mockServer(health = 404)))
        assertEquals(TvSetupError.RateLimited, errorOf(mockServer(pairStart = 429)))
        assertEquals(TvSetupError.Unreachable, errorOf(mockServer(pairStart = 500)))
        val closed = MockWebServer().apply { start() }
        val port = closed.port
        closed.shutdown()
        assertEquals(TvSetupError.Unreachable, errorOf(null, "http://127.0.0.1:$port"))
        assertEquals(TvSetupError.BadRequest, errorOf(null, "http://exa mple"))
    }

    @Test
    fun senderTalksToTheListenerAndMapsErrors() = runBlocking {
        val (_, link) = listen(handler = TvSetupHandler { server, sid ->
            if (server.contains("wrong")) throw TvSetupException(TvSetupError.ServerMismatch)
            TvSetupAccepted(TvSetupAnswer("abc234", sid!!, server, "Гостиная"))
        })
        val sender = TvSetupSender()
        try {
            sender.send(link, "http://wrong", serverId)
            fail("no error")
        } catch (e: TvSetupFailure) {
            assertEquals(TvSetupError.ServerMismatch, e.error)
            assertEquals(409, e.status)
        }
        val answer = sender.send(link, "http://192.168.1.10:8420", serverId)
        assertEquals(TvSetupAnswer("abc234", serverId, "http://192.168.1.10:8420", "Гостиная"), answer)

        // Then lookup + approve on the phone's own server; a code of another server is refused.
        val calls = mutableListOf<String>()
        val approver = object : TvSetupApprover {
            override suspend fun lookup(code: String) = app.antresol.core.model.PairLookup(code).also { calls += "lookup $code" }
            override suspend fun approve(code: String, userId: Long) = app.antresol.core.model.PairApproved(true, userId).also { calls += "approve $code $userId" }
        }
        val (_, link2) = listen(handler = TvSetupHandler { server, _ -> TvSetupAccepted(TvSetupAnswer("ABC234", "e".repeat(32), server, "TV")) })
        try {
            sendTvSetup(sender::send, link2, "http://192.168.1.10:8420", serverId)
            fail("no error")
        } catch (_: TvSetupOtherServerException) {
        }
        assertTrue(calls.isEmpty())
        val (_, link3) = listen(handler = TvSetupHandler { server, sid -> TvSetupAccepted(TvSetupAnswer("ABC234", sid!!, server, "TV")) })
        approveTvCode(approver, sendTvSetup(sender::send, link3, "http://192.168.1.10:8420", serverId).code, 7)
        assertEquals(listOf("lookup ABC234", "approve ABC234 7"), calls)
    }

    // endregion

    @Test
    fun pairingFlowStartsWithTheHandedOverCode() = runTest {
        var starts = 0
        val api = object : PairingApi {
            override suspend fun start(device: DeviceInfo): PairStart {
                starts++
                return PairStart("XYZ789", "s2", url = "u2", serverId = serverId)
            }

            override suspend fun poll(secret: String): PairPoll =
                if (secret == "s1") PairPoll.Approved("tok", Me(id = 1, name = "Alex")) else PairPoll.Pending(2)
        }
        val initial = StartedPairing(PairStart("abc234", "s1", url = "u1", serverId = serverId), receivedAtMs = 0)
        val states = PairingFlow(api, { DeviceInfo("dev-00000001") }, now = { 0 }, initial = initial).states().take(2).toList()
        assertEquals(PairingState.Pending("ABC234", "u1", serverId, PairingFlow.DEFAULT_TTL_MS), states[0])
        assertTrue(states[1] is PairingState.Approved)
        assertEquals(0, starts)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out")
            Thread.sleep(20)
        }
    }
}

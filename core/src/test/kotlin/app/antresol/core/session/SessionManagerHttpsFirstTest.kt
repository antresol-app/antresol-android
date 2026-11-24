package app.antresol.core.session

import app.antresol.core.model.DeviceInfo
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** A bare host outside the home network is tried over https first; only then the default http://host:8420. */
class SessionManagerHttpsFirstTest {
    private val asked = ArrayList<String>()

    /** What each request's call was allowed to take (nanoseconds, 0 = no limit), by URL. */
    private val callTimeouts = LinkedHashMap<String, Long>()

    /** Answers like a server for [serving] (base URLs) and refuses to connect anywhere else. */
    private fun TestScope.manager(vararg serving: String, needsSetup: Boolean = false, notAntresol: Set<String> = emptySet(), unresolvable: Set<String> = emptySet()): SessionManager {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request: Request = chain.request()
            val base = request.url.scheme + "://" + request.url.host + (if (request.url.port != defaultPort(request.url.scheme)) ":${request.url.port}" else "")
            asked += request.url.toString()
            callTimeouts[request.url.toString()] = chain.call().timeout().timeoutNanos()
            if (request.url.host in unresolvable) throw UnknownHostException(request.url.host)
            if (base in notAntresol) return@addInterceptor answer(request, 404, """{"error":"no"}""")
            if (base !in serving) throw IOException("Failed to connect to ${request.url.host}")
            val body = if (request.url.encodedPath == "/api/health") {
                """{"ok":true,"serverId":"srv1","version":"0.3.0"}"""
            } else {
                """{"needsSetup":$needsSetup,"remote":false}"""
            }
            answer(request, 200, body)
        }.build()
        return SessionManager(
            store = InMemorySessionStore(deviceId = "test-device-0001"),
            deviceInfo = { DeviceInfo(it, "Android TV (Test)", "Test", "0.1.0") },
            language = { "en" },
            scope = this,
            httpClient = http,
        )
    }

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

    private fun answer(request: Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("answer")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    @Test
    fun aBareHostThatAnswersOverHttpsStaysOnHttps() = runTest {
        val checked = manager("https://media.example.com").check("media.example.com")
        assertEquals("https://media.example.com", checked.baseUrl)
        assertEquals(listOf("https://media.example.com/api/health", "https://media.example.com/api/setup"), asked)
    }

    @Test
    fun aBareHostWithoutHttpsFallsBackToTheDefaultHttpAddress() = runTest {
        val checked = manager("http://media.example.com:8420").check("media.example.com")
        assertEquals("http://media.example.com:8420", checked.baseUrl)
        assertEquals(
            listOf("https://media.example.com/api/health", "http://media.example.com:8420/api/health", "http://media.example.com:8420/api/setup"),
            asked,
        )
    }

    @Test
    fun theHttpsTryIsBoundedAndTheFallbackIsNot() = runTest {
        manager("http://media.example.com:8420").check("media.example.com")
        assertEquals(TimeUnit.SECONDS.toNanos(8), callTimeouts["https://media.example.com/api/health"])
        // The plain address keeps the client's own limits (no call timeout of its own).
        assertEquals(0L, callTimeouts["http://media.example.com:8420/api/health"])
        assertEquals(0L, callTimeouts["http://media.example.com:8420/api/setup"])
    }

    @Test
    fun aNameThatDoesNotResolveIsNotAskedAgainOverHttp() = runTest {
        val manager = manager("http://media.example.com:8420", unresolvable = setOf("media.example.com"))
        try {
            manager.check("media.example.com")
            fail("expected UnknownHostException")
        } catch (_: UnknownHostException) {
        }
        assertEquals(listOf("https://media.example.com/api/health"), asked)
    }

    @Test
    fun somethingElseOnPort443IsNotAnAntresolServerSoTheFallbackIsTried() = runTest {
        val checked = manager("http://media.example.com:8420", "https://media.example.com", notAntresol = setOf("https://media.example.com")).check("media.example.com")
        assertEquals("http://media.example.com:8420", checked.baseUrl)
    }

    @Test
    fun anAntresolServerOverHttpsThatNeedsSetupIsReportedNotSkipped() = runTest {
        val manager = manager("https://media.example.com", "http://media.example.com:8420", needsSetup = true)
        try {
            manager.check("media.example.com")
            fail("expected ServerNeedsSetupException")
        } catch (_: ServerNeedsSetupException) {
        }
        assertEquals(listOf("https://media.example.com/api/health", "https://media.example.com/api/setup"), asked)
    }

    @Test
    fun nothingAnswersAnywhereTheHttpErrorIsThrown() = runTest {
        try {
            manager().check("media.example.com")
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(listOf("https://media.example.com/api/health", "http://media.example.com:8420/api/health"), asked)
    }

    @Test
    fun hostsAtHomeAndAddressesWithAChosenSchemeOrPortAreNotProbedOverHttps() = runTest {
        for ((typed, expected) in listOf(
            "192.168.1.5" to "http://192.168.1.5:8420",
            "nas.local" to "http://nas.local:8420",
            "media.example.com:9000" to "http://media.example.com:9000",
            "http://media.example.com" to "http://media.example.com",
        )) {
            asked.clear()
            assertEquals(expected, manager(expected).check(typed).baseUrl)
            assertEquals(typed, listOf("$expected/api/health", "$expected/api/setup"), asked)
        }
    }
}

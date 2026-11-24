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

/** A bare host outside the home network is tried over https first; only then the default http://host:8420. */
class SessionManagerHttpsFirstTest {
    private val asked = ArrayList<String>()

    /** Answers like a server for [serving] (base URLs) and refuses to connect anywhere else. */
    private fun TestScope.manager(vararg serving: String, needsSetup: Boolean = false, notAntresol: Set<String> = emptySet()): SessionManager {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request: Request = chain.request()
            val base = request.url.scheme + "://" + request.url.host + (if (request.url.port != defaultPort(request.url.scheme)) ":${request.url.port}" else "")
            asked += request.url.toString()
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

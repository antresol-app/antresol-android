package app.antresol.core.ways

import app.antresol.core.api.AntresolClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The catalog asks the server once per sign-in, and again for another profile, a refresh, or after a network failure. */
class WaysCatalogTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AntresolClient
    @Volatile private var networkDown = false
    private var addonsAnswer: () -> MockResponse = { MockResponse().setBody("""[{"id":"alpha","entry":"/e.js"}]""") }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/addons/web" -> addonsAnswer()
                "/api/addons/alpha/x/app" -> MockResponse().setBody("""{"v":1,"ways":[{"id":"w","label":"Way","kinds":["movie"]}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        // An interceptor stands in for a network that is down.
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            if (networkDown) throw java.io.IOException("down") else chain.proceed(chain.request())
        }.build()
        client = AntresolClient(server.url("/").toString(), httpClient = http, token = "profile-1")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun asksOnceAndKeepsItForTheSession() = runTest {
        val catalog = WaysCatalog { client }
        assertNull(catalog.peek())
        assertEquals(listOf("alpha/w"), catalog.get().ways.map { it.key })
        catalog.get()
        catalog.get()
        assertEquals(2, server.requestCount)
        assertEquals(listOf("alpha/w"), catalog.peek()!!.ways.map { it.key })
    }

    @Test
    fun anotherProfileOrARefreshAsksAgain() = runTest {
        val catalog = WaysCatalog { client }
        catalog.get()
        client.token = "profile-2"
        assertNull(catalog.peek())
        catalog.get()
        assertEquals(4, server.requestCount)
        catalog.invalidate()
        catalog.get()
        assertEquals(6, server.requestCount)
    }

    @Test
    fun aKidsForbiddenAnswerIsKeptAsNoWays() = runTest {
        addonsAnswer = { MockResponse().setResponseCode(403).setBody("""{"error":"no"}""") }
        val catalog = WaysCatalog { client }
        assertTrue(catalog.get().isEmpty)
        catalog.get()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aNetworkFailureIsAskedAgainNextTime() = runTest {
        val catalog = WaysCatalog { client }
        networkDown = true
        assertTrue(catalog.get().isEmpty)
        networkDown = false
        assertEquals(1, catalog.get().ways.size)
    }

    @Test
    fun withoutAClientThereAreNoWays() = runTest {
        assertTrue(WaysCatalog { null }.get().isEmpty)
    }
}

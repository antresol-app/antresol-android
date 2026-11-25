package app.antresol.core.family

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.api.ServerLanguage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The two calls of «Семья»: what they send and how the server's answers are read. */
class FamilyClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AntresolClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = AntresolClient(server.url("/").toString(), language = { ServerLanguage.RU }, token = "tok")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun sample(name: String) = javaClass.classLoader.getResource("api-samples/$name.json")!!.readText()

    @Test
    fun theFamilyOfAnItemIsReadFromItsOwnPath() = runTest {
        enqueue(sample("family"))
        val members = client.family(77)
        val request = server.takeRequest()
        assertEquals("GET /api/items/77/family", "${request.method} ${request.path}")
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals(listOf("Sam", "Robin", "Living Room", "Kim"), members.map { it.name })
    }

    @Test
    fun aKidOrAQuietHouseholdIsAnEmptyList() = runTest {
        enqueue("""{"members":[]}""")
        assertEquals(emptyList<Any>(), client.family(77))
        // A server that sends no list at all reads the same.
        enqueue("{}")
        assertEquals(emptyList<Any>(), client.family(77))
    }

    @Test
    fun adoptingNamesTheProfileAndAnswersTheNewProgressOfThisOne() = runTest {
        enqueue("""{"positionMs":2040000,"durationMs":2700000,"finished":false,"locator":"","fraction":0.76,"updatedAt":"2026-11-25T19:00:00Z"}""")
        val mine = client.adoptProgress(601, 2)
        val request = server.takeRequest()
        assertEquals("POST /api/progress/601/adopt", "${request.method} ${request.path}")
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("2", body["userId"]!!.jsonPrimitive.content)
        assertEquals(setOf("userId"), body.keys)
        assertEquals(2_040_000L, mine.positionMs)
        assertTrue(mine.hasProgress)
    }

    @Test
    fun aProfileWithNothingToTakeIsRefusedWithTheServersWords() = runTest {
        enqueue("""{"error":"Not found"}""", code = 404)
        try {
            client.adoptProgress(601, 9)
            fail("expected a 404")
        } catch (e: ApiException) {
            assertEquals(404, e.status)
        }
    }
}

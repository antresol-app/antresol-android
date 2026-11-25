package app.antresol.core.requests

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.api.ServerLanguage
import app.antresol.core.model.KidAsk
import app.antresol.core.model.KidCandidate
import app.antresol.core.model.RequestSearchKind
import app.antresol.core.model.RequestStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The kid-request calls: paths, queries, bodies, and how the server's answers are read. */
class RequestsClientTest {
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

    private fun bodyOf(request: okhttp3.mockwebserver.RecordedRequest): JsonObject = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun searchAsksOneKindAndKeepsTheQueryAsText() = runTest {
        enqueue(sample("requests-search"))
        val found = client.requestSearch("far & away", RequestSearchKind.VIDEO)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/requests/search?q=far%20%26%20away&kind=video", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals("ru", request.getHeader("Accept-Language"))
        assertEquals(3, found.size)
        assertNull(found[0].kidRequest?.request)
        assertEquals(RequestStatus.PENDING, found[1].kidRequest?.request?.status)
    }

    @Test
    fun askingSendsAMovieByItsTmdbIdAndAnAlbumAsTheCatalogueShowedIt() = runTest {
        val answer = """{"id":3,"key":"tmdb:603","kind":"movie","tmdbId":603,"title":"Far Away Film","status":"pending","createdAt":"2026-11-25T10:00:00Z"}"""
        enqueue(answer)
        val made = client.askRequest(KidAsk(kind = "movie", tmdbId = 603, title = "Far Away Film"))
        assertEquals(3L, made.id)
        assertEquals(RequestStatus.PENDING, made.status)
        val movie = server.takeRequest()
        assertEquals("POST /api/requests", "${movie.method} ${movie.path}")
        assertTrue(movie.getHeader("Content-Type")!!.startsWith("application/json"))
        val movieBody = bodyOf(movie)
        assertEquals("movie", movieBody["kind"]!!.jsonPrimitive.content)
        assertEquals("603", movieBody["tmdbId"]!!.jsonPrimitive.content)

        // As a search result of an album: its catalogue id, the artist and the other names go with it.
        val candidate = AntresolClient.json.decodeFromString(ListSerializer(KidCandidate.serializer()), sample("requests-search")).last()
        enqueue(answer.replace("movie", "music"))
        client.askRequest(candidate.ask())
        val album = bodyOf(server.takeRequest())
        assertEquals("music", album["kind"]!!.jsonPrimitive.content)
        assertEquals("mb-1", album["extId"]!!.jsonPrimitive.content)
        assertEquals("Test Artist", album["subtitle"]!!.jsonPrimitive.content)
        assertEquals("""["The Blue Album"]""", album["aliases"].toString())
    }

    @Test
    fun anAgeLimitIsRefusedWithTheServersWordsAndTheRatingCode() = runTest {
        enqueue("""{"error":"This is 16+, and this profile allows up to 6+","code":"rating"}""", code = 403)
        try {
            client.askRequest(KidAsk(kind = "movie", tmdbId = 603, title = "Far Away Film"))
            fail("the server refused")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
            assertEquals("rating", e.code)
            assertEquals("This is 16+, and this profile allows up to 6+", e.message)
        }
    }

    @Test
    fun theKidsOwnListAndTheAdministratorsOnes() = runTest {
        enqueue(sample("requests-mine"))
        assertEquals(listOf(9L, 7L, 5L), client.myRequests().map { it.id })
        assertEquals("/api/requests/mine", server.takeRequest().path)

        enqueue(sample("requests-all"))
        val all = client.allRequests()
        assertEquals("/api/requests", server.takeRequest().path)
        assertEquals("Sam", all.first().user?.name)

        enqueue(sample("requests-count"))
        assertEquals(2, client.requestCount().pending)
        assertEquals("/api/requests/count", server.takeRequest().path)
    }

    @Test
    fun approvingAndDecliningAreTheAdministratorsPostsOfOneRequest() = runTest {
        val answered = """{"id":10,"key":"ext:mb-1","kind":"music","title":"Test Album","status":"approved","createdAt":"2026-11-25T09:00:00Z"}"""
        enqueue(answered)
        assertEquals(RequestStatus.APPROVED, client.approveRequest(10).status)
        val approve = server.takeRequest()
        assertEquals("POST /api/requests/10/approve", "${approve.method} ${approve.path}")

        enqueue(answered.replace("approved", "declined").replace("}", ""","reason":"Not yet"}"""))
        val declined = client.declineRequest(10, "Not yet")
        assertEquals(RequestStatus.DECLINED, declined.status)
        assertEquals("Not yet", declined.reason)
        val decline = server.takeRequest()
        assertEquals("POST /api/requests/10/decline", "${decline.method} ${decline.path}")
        assertEquals("Not yet", bodyOf(decline)["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun anAnswerThatCameFirstIsAConflict() = runTest {
        enqueue("""{"error":"Already answered"}""", code = 409)
        try {
            client.declineRequest(10, "")
            fail("conflict")
        } catch (e: ApiException) {
            assertEquals(409, e.status)
        }
    }

    @Test
    fun theRealApiMapsEveryCall() = runTest {
        val api = client.requestsApi()
        enqueue("[]")
        assertTrue(api.search("x", RequestSearchKind.BOOK).isEmpty())
        assertEquals("/api/requests/search?q=x&kind=book", server.takeRequest().path)
        enqueue(sample("requests-count"))
        assertEquals(2, api.pending())
        enqueue("[]")
        assertTrue(api.mine().isEmpty())
        enqueue("[]")
        assertTrue(api.all().isEmpty())
    }
}

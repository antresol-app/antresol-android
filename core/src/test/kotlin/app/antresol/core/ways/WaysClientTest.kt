package app.antresol.core.ways

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.api.ServerLanguage
import app.antresol.core.model.InputPlay
import app.antresol.core.model.InputProgressBody
import app.antresol.core.model.PlayRequest
import app.antresol.core.model.PlaybackCaps
import app.antresol.core.model.RunRequest
import app.antresol.core.model.StepRequest
import app.antresol.core.model.WayCancelRequest
import app.antresol.core.model.WayProgressBody
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The `x/app` calls and the core's input player: paths, queries, bodies and how answers are read. */
class WaysClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AntresolClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = AntresolClient(server.url("/").toString(), language = { ServerLanguage.RU }, token = "tok")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(body: String = "", code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun json(request: RecordedRequest): JsonObject = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun discoveryCallsKeepTheLanguageAndTheToken() = runTest {
        enqueue("""[{"id":"alpha","entry":"/e.js"}]""")
        enqueue("""{"v":1,"ways":[{"id":"w","label":"Way","kinds":["movie"]}],"search":{"label":"Find"}}""")
        assertEquals(listOf("alpha"), client.addonsWeb().map { it.id })
        val info = client.appInfo("alpha")
        assertEquals("Find", info.search?.label)
        assertEquals(listOf("movie"), info.ways.single().kinds)

        val first = server.takeRequest()
        assertEquals("GET /api/addons/web", "${first.method} ${first.path}")
        assertEquals("ru", first.getHeader("Accept-Language"))
        assertEquals("Bearer tok", first.getHeader("Authorization"))
        assertEquals("/api/addons/alpha/x/app", server.takeRequest().path)
    }

    @Test
    fun addonIdIsASegmentOfItsOwn() = runTest {
        enqueue("""{"v":1}""")
        client.appInfo("a b/c")
        assertEquals("/api/addons/a%20b%2Fc/x/app", server.takeRequest().path)
    }

    @Test
    fun searchStepRunStateAndCancelShapes() = runTest {
        enqueue("""{"titles":[]}""")
        client.waySearch("alpha", "the matrix & more")
        assertEquals("/api/addons/alpha/x/app/search?q=the%20matrix%20%26%20more", server.takeRequest().path)

        enqueue("""{"title":"Source","rows":[{"ref":"r","label":"One","final":true}]}""")
        val step = client.wayStep("alpha", StepRequest("watch", "series", 1399, season = 2, ref = "prev"))
        assertTrue(step.rows.single().isFinal)
        val stepRequest = server.takeRequest()
        assertEquals("POST", stepRequest.method)
        assertEquals("/api/addons/alpha/x/app/step", stepRequest.path)
        assertTrue(stepRequest.getHeader("Content-Type")!!.startsWith("application/json"))
        val stepBody = json(stepRequest)
        assertEquals("watch", stepBody["way"]!!.jsonPrimitive.content)
        assertEquals("series", stepBody["kind"]!!.jsonPrimitive.content)
        assertEquals("1399", stepBody["tmdbId"]!!.jsonPrimitive.content)
        assertEquals("2", stepBody["season"]!!.jsonPrimitive.content)
        assertEquals("prev", stepBody["ref"]!!.jsonPrimitive.content)
        assertFalse("an absent episode is not sent", stepBody.containsKey("episode"))

        enqueue("""{"pick":true}""")
        assertTrue(client.wayRun("alpha", RunRequest("watch", "movie", 603, quick = true, maxHeight = 1080)).pick)
        val run = server.takeRequest()
        assertEquals("/api/addons/alpha/x/app/run", run.path)
        val runBody = json(run)
        assertTrue(runBody["quick"]!!.jsonPrimitive.boolean)
        assertEquals("1080", runBody["maxHeight"]!!.jsonPrimitive.content)
        assertFalse(runBody.containsKey("ref"))
        assertFalse(runBody.containsKey("refresh"))

        enqueue("""{"ways":{"keep":{"active":true,"fraction":0.5}}}""")
        assertEquals(0.5, client.wayState("alpha", "series", 1399, season = 1).ways.getValue("keep").fraction!!, 0.0)
        assertEquals("/api/addons/alpha/x/app/state?kind=series&tmdbId=1399&season=1", server.takeRequest().path)

        enqueue("""{"done":"Cancelled"}""")
        assertEquals("Cancelled", client.wayCancel("alpha", WayCancelRequest("keep", "movie", 603)).done)
        val cancel = server.takeRequest()
        assertEquals("POST /api/addons/alpha/x/app/cancel", "${cancel.method} ${cancel.path}")
        assertEquals("keep", json(cancel)["way"]!!.jsonPrimitive.content)
    }

    @Test
    fun progressAndStopOfAStreamTheAddonServes() = runTest {
        enqueue(code = 204)
        client.wayProgress(
            "alpha",
            WayProgressBody("sess1", "watch", "series", 1399, season = 1, episode = 3, positionMs = 61_000, durationMs = 2_700_000, finished = true),
        )
        val put = server.takeRequest()
        assertEquals("PUT /api/addons/alpha/x/app/progress", "${put.method} ${put.path}")
        val body = json(put)
        assertEquals("sess1", body["session"]!!.jsonPrimitive.content)
        assertEquals("3", body["episode"]!!.jsonPrimitive.content)
        assertEquals("61000", body["positionMs"]!!.jsonPrimitive.content)
        assertTrue(body["finished"]!!.jsonPrimitive.boolean)

        enqueue(code = 204)
        client.wayStop("alpha", "sess1")
        val delete = server.takeRequest()
        assertEquals("DELETE /api/addons/alpha/x/app/play/sess1", "${delete.method} ${delete.path}")
    }

    @Test
    fun anAddonThatDoesNotSpeakTheProtocolIsA404() = runTest {
        enqueue("""{"error":"not found"}""", code = 404)
        try {
            client.appInfo("gamma")
            fail("expected a 404")
        } catch (e: ApiException) {
            assertTrue(e.isNotFound)
        }
    }

    @Test
    fun inputPlayAnswers202WhileItIsPreparedAndThenAsPlay() = runTest {
        enqueue("""{"pending":true}""", code = 202)
        enqueue("""{"mode":"direct","url":"/api/play/input/in-1/direct","startSec":5,"durationMs":60000}""")
        val request = PlayRequest(PlaybackCaps.EXOPLAYER_BASELINE, startSec = 5.0)
        assertEquals(InputPlay.Pending, client.inputPlay("in-1", request))
        val ready = client.inputPlay("in-1", request) as InputPlay.Ready
        assertEquals("/api/play/input/in-1/direct", ready.play.url)
        assertFalse(ready.play.isHls)
        val post = server.takeRequest()
        assertEquals("POST /api/play/input/in-1", "${post.method} ${post.path}")
        assertTrue(json(post).containsKey("caps"))
    }

    @Test
    fun inputInfoProgressAndTitle() = runTest {
        enqueue("""{"id":"in-1","addonId":"alpha","name":"A/B.mkv","itemId":7,"slow":false,"startSec":3.5}""")
        assertEquals(3.5, client.inputInfo("in-1").startSec, 0.0)
        assertEquals("/api/play/input/in-1", server.takeRequest().path)

        enqueue(code = 204)
        client.inputProgress("in-1", InputProgressBody(1000, 5000, keepalive = true))
        val put = server.takeRequest()
        assertEquals("PUT /api/play/input/in-1/progress", "${put.method} ${put.path}")
        assertTrue(json(put)["keepalive"]!!.jsonPrimitive.boolean)

        enqueue("""{"tmdbId":603,"kind":"movie","title":"The Matrix","inLibrary":true,"libraryItemId":42}""")
        val title = client.title("movie", 603)
        assertEquals(42L, title.libraryItemId)
        assertEquals("/api/title/movie/603", server.takeRequest().path)
        assertNull(server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS))
    }
}

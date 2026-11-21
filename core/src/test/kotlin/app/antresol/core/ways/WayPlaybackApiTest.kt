package app.antresol.core.ways

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.RunRequest
import app.antresol.core.playback.PlaybackReporter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** How a closing player reports a video that an addon serves: the progress routes and the session stop. */
class WayPlaybackApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AntresolClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = AntresolClient(server.url("/").toString(), token = "tok")
        repeat(10) { server.enqueue(MockResponse().setResponseCode(204)) }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun body() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun inputProgressIsPeriodicWhilePlayingAndKeepaliveOnTheLastWrite() = runTest {
        val api = client.inputPlaybackApi("in-1")
        api.saveProgress(0, 61_000, 3_600_000)
        val periodic = body()
        assertEquals("61000", periodic["positionMs"]!!.jsonPrimitive.content)
        assertFalse(periodic["keepalive"]!!.jsonPrimitive.boolean)
        api.saveFinalProgress(0, 62_000, 3_600_000)
        assertTrue(body()["keepalive"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun anInputPositionNeverPassesItsDuration() = runTest {
        // The server refuses a position more than 5 s past the duration; a player's duration is an estimate.
        client.inputPlaybackApi("in-1").saveProgress(0, 70_000, 0)
        val sent = body()
        assertEquals("70000", sent["durationMs"]!!.jsonPrimitive.content)
    }

    @Test
    fun theReporterSendsItsFinalWriteAsTheLastOne() = runTest {
        val reporter = PlaybackReporter(
            api = client.inputPlaybackApi("in-1"),
            scope = this,
            itemId = 0,
            durationMs = 100_000,
            positionMs = { 50_000 },
            isPlaying = { false },
        )
        reporter.start(null)
        reporter.flush()
        assertFalse(body()["keepalive"]!!.jsonPrimitive.boolean)
        // The position moved, so the closing write goes out, as the last one.
        val moved = PlaybackReporter(client.inputPlaybackApi("in-1"), this, 0, 100_000, { 90_000 }, { false })
        moved.stop()
        assertTrue(body()["keepalive"]!!.jsonPrimitive.boolean)
        reporter.stop()
    }

    @Test
    fun aDirectStreamReportsUnderItsSessionAndFinishedOnlyWhenItEnded() = runTest {
        var ended = false
        val request = RunRequest("watch", "series", 1399, season = 1, episode = 4)
        val api = client.wayPlaybackApi("alpha", request, "sess1") { ended }
        api.saveProgress(0, 10_000, 600_000)
        val first = server.takeRequest()
        assertEquals("/api/addons/alpha/x/app/progress", first.path)
        val sent = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
        assertEquals("sess1", sent["session"]!!.jsonPrimitive.content)
        assertEquals("4", sent["episode"]!!.jsonPrimitive.content)
        assertNull(sent["finished"])
        ended = true
        api.saveProgress(0, 600_000, 600_000)
        assertTrue(body()["finished"]!!.jsonPrimitive.boolean)
        api.stopSession("sess1")
        val stop = server.takeRequest()
        assertEquals("DELETE /api/addons/alpha/x/app/play/sess1", "${stop.method} ${stop.path}")
    }
}

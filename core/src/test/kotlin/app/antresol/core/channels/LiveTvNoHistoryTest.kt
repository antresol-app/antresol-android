package app.antresol.core.channels

import app.antresol.core.api.AntresolClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * TV is TV (spec item D): the live channel session, with the real [AntresolClient], talks to the
 * server only through the channel routes. It never reports progress, playback sessions, watched
 * flags, track memory (`PUT /api/items/{id}/track-prefs`) or scrobbles; its only writes are
 * `PUT /api/channels/last` and the favourite. Every request it makes is recorded and checked.
 */
class LiveTvNoHistoryTest {
    private lateinit var server: MockWebServer
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var opens = 0

    private val channelsJson = """{"channels":[
        {"id":1,"number":1,"name":"Все фильмы","category":"Кино","favorite":true,
         "now":{"id":"1-1","itemId":18,"kind":"movie","title":"Морской бриз","start":"2026-11-11T22:26:58Z","end":"2099-01-01T00:00:00Z",
                "audio":[{"slot":"ru","name":"Русский","label":"Русский — LostFilm","lang":"ru"}]}},
        {"id":2,"number":2,"name":"Комедии","category":"Жанры"},
        {"id":3,"number":3,"name":"Драмы","category":"Жанры","favorite":true}],
        "lastChannel":1,"canManage":false,"canCreate":false}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                requests += "${request.method} $path"
                return when {
                    path == "/api/channels" -> json(channelsJson)
                    path.matches(Regex("/api/channels/\\d+/guide")) -> json("""{"channel":{"id":1},"programs":[]}""")
                    path == "/api/auth/media-key" -> json("""{"key":"k","expiresAt":"2099-01-01T00:00:00Z"}""")
                    path.startsWith("/api/channels/live/") -> {
                        // The first open of each channel answers «starting», as the real encoder does.
                        if (opens++ % 2 == 0) MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("""{"error":"starting"}""")
                        else MockResponse().setBody("#EXTM3U\n")
                    }
                    path == "/api/me/playback-prefs" -> json("""{"audioLangs":["ru"],"subMode":"off","subLangs":[]}""")
                    path == "/api/channels/last" || path.endsWith("/favorite") -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun theLiveSessionOnlyUsesChannelRoutes() = runBlocking {
        val client = AntresolClient(server.url("/").toString(), token = "device-token")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = LiveTvSession(client.liveApi(), scope, channelId = 1, filter = ChannelFilter.Favorites)
            session.start()
            awaitReady(session)
            session.zap(1)
            awaitReady(session)
            session.tune(2)
            awaitReady(session)
            session.setFavorite(2, true)
            session.setFavorite(2, false)
            session.streamFailed()
            awaitReady(session)
            session.stop()
            session.start()
            awaitReady(session)
            delay(300)
        } finally {
            scope.cancel()
        }

        val seen = requests.toList()
        val allowed = listOf(
            Regex("GET /api/channels"),
            Regex("GET /api/channels/\\d+/guide"),
            Regex("GET /api/channels/live/\\d+/index\\.m3u8"),
            Regex("POST /api/auth/media-key"),
            Regex("GET /api/me/playback-prefs"),
            Regex("PUT /api/channels/last"),
            Regex("(PUT|DELETE) /api/channels/\\d+/favorite"),
        )
        val outside = seen.filterNot { request -> allowed.any { it.matches(request) } }
        assertEquals("requests outside the channel routes: $outside", emptyList<String>(), outside)
        val forbidden = Regex("/api/(progress|play/|hls/|items/\\d+/(watched|track-prefs)|music/plays|watchlist|scrobble)")
        assertTrue(seen.none { forbidden.containsMatchIn(it) })
        // The session did write the last channel and the favourites, and nothing else.
        assertTrue(seen.contains("PUT /api/channels/last"))
        assertEquals(setOf("PUT /api/channels/last", "PUT /api/channels/2/favorite", "DELETE /api/channels/2/favorite", "POST /api/auth/media-key"), seen.filterNot { it.startsWith("GET ") }.toSet())
    }

    private suspend fun awaitReady(session: LiveTvSession) = withTimeout(10_000) {
        while (session.state.value.stream !is LiveStream.Ready) delay(20)
    }
}

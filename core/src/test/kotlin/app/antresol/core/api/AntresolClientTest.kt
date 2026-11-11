package app.antresol.core.api

import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.PlayRequest
import app.antresol.core.model.PlaybackCaps
import app.antresol.core.model.PlaylistPatch
import app.antresol.core.model.ProgressUpdate
import app.antresol.core.model.ShuffleSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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

/** Request shape tests against a mock server: headers, paths, query strings and bodies. */
class AntresolClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AntresolClient
    private var unauthorizedCalls = 0

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = AntresolClient(
            baseUrl = server.url("/").toString(),
            language = { ServerLanguage.RU },
            onUnauthorized = { unauthorizedCalls++ },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(body: String = "", code: Int = 200, headers: Map<String, String> = emptyMap()) {
        val response = MockResponse().setResponseCode(code).setBody(body)
        headers.forEach { (k, v) -> response.addHeader(k, v) }
        server.enqueue(response)
    }

    private fun takeJson(request: RecordedRequest): JsonObject = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun signInSendsDeviceObjectAndStoresToken() = runTest {
        enqueue("""{"token":"tok123","user":{"id":1,"name":"Admin","avatar":"grad-1","isAdmin":true}}""")
        val response = client.signIn(1, "admin123", DeviceInfo("abcd-1234-efgh", "TV", "Model", "0.1.0"))
        assertEquals("tok123", response.token)
        assertEquals("tok123", client.token)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/token", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Origin"))
        assertEquals("ru", request.getHeader("Accept-Language"))
        val body = takeJson(request)
        assertEquals(1, body["userId"]!!.jsonPrimitive.int)
        assertEquals("admin123", body["password"]!!.jsonPrimitive.content)
        assertEquals("abcd-1234-efgh", body["device"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun passwordlessSignInOmitsPassword() = runTest {
        enqueue("""{"token":"t","user":{"id":2}}""")
        client.signIn(2, "", DeviceInfo("abcd-1234-efgh"))
        assertFalse(takeJson(server.takeRequest()).containsKey("password"))
    }

    @Test
    fun authenticatedCallsCarryBearer() = runTest {
        client.token = "secret"
        enqueue("""{"id":1,"name":"Admin"}""")
        client.me()
        val request = server.takeRequest()
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals("/api/me", request.path)
    }

    @Test
    fun wrongPasswordDoesNotTriggerUnauthorizedCallback() = runTest {
        enqueue("""{"error":"Wrong password"}""", code = 401)
        try {
            client.signIn(1, "nope", DeviceInfo("abcd-1234-efgh"))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.status)
            assertEquals("Wrong password", e.message)
        }
        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun revokedTokenTriggersUnauthorizedCallback() = runTest {
        client.token = "revoked"
        enqueue("""{"error":"Sign in required"}""", code = 401)
        runCatching { client.home() }
        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun errorsCarryCodeAndRetryAfter() = runTest {
        client.token = "t"
        enqueue("""{"error":"busy","code":"x"}""", code = 429, headers = mapOf("Retry-After" to "3"))
        try {
            client.play(5, PlayRequest(PlaybackCaps.EXOPLAYER_BASELINE))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.isTooManySessions)
            assertEquals("x", e.code)
            assertEquals(3L, e.retryAfterSec)
        }
    }

    @Test
    fun nonJsonErrorFallsBackToStatus() = runTest {
        enqueue("404 page not found", code = 404)
        try {
            client.health()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("HTTP 404", e.message)
        }
    }

    @Test
    fun playBodyOmitsNullsAndKeepsCaps() = runTest {
        client.token = "t"
        enqueue("""{"mode":"direct","url":"/api/stream/12","startSec":30,"durationMs":60046}""")
        val play = client.play(12, PlayRequest(PlaybackCaps.EXOPLAYER_BASELINE, startSec = 30.0, audioIndex = 2))
        assertEquals("/api/stream/12", play.url)
        val request = server.takeRequest()
        assertEquals("/api/play/12", request.path)
        val body = takeJson(request)
        assertEquals(setOf("caps", "startSec", "audioIndex"), body.keys)
        assertEquals(listOf("mp4", "mov", "mkv", "webm"), body["caps"]!!.jsonObject["containers"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(body["caps"]!!.jsonObject.containsKey("maxHeight"))
    }

    @Test
    fun bodilessMutationsSendNoContentType() = runTest {
        client.token = "t"
        enqueue(code = 204)
        client.addToWatchlist(7)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/watchlist/7", request.path)
        assertNull(request.getHeader("Content-Type"))
    }

    @Test
    fun hlsKeepaliveAndStop() = runTest {
        client.token = "t"
        enqueue("""{"segments":10,"running":true,"paused":false}""")
        enqueue(code = 204)
        assertEquals(10, client.hlsKeepalive("abc").segments)
        client.hlsStop("abc")
        assertEquals("PUT /api/hls/abc", server.takeRequest().let { "${it.method} ${it.path}" })
        assertEquals("DELETE /api/hls/abc", server.takeRequest().let { "${it.method} ${it.path}" })
    }

    @Test
    fun progressBodies() = runTest {
        client.token = "t"
        enqueue(code = 204)
        enqueue(code = 204)
        enqueue(code = 204)
        client.saveProgress(19, ProgressUpdate.playback(15000, 60046))
        client.saveProgress(21, ProgressUpdate.reader("page:2", 0.6667))
        client.setWatched(19, false)
        assertEquals(setOf("positionMs", "durationMs"), takeJson(server.takeRequest()).keys)
        assertEquals(setOf("locator", "fraction"), takeJson(server.takeRequest()).keys)
        val watched = server.takeRequest()
        assertEquals("/api/items/19/watched", watched.path)
        assertEquals("false", takeJson(watched)["watched"]!!.jsonPrimitive.content)
    }

    @Test
    fun listQueries() = runTest {
        client.token = "t"
        repeat(5) { enqueue("""{"items":[],"total":0}""") }
        enqueue("""{"tracks":[]}""")
        enqueue("""{"library":[]}""")
        client.items(kind = "movie", sort = "title", limit = 50, offset = 100)
        client.albums(sort = "year", genre = "Hip Hop", artistId = 13, starred = true)
        client.artists()
        client.songs(starred = true)
        client.books(kind = "audiobook", sort = "author")
        client.shuffle(ShuffleSource.Genre("Rock"))
        client.search("Big Buck")
        assertEquals("/api/items?kind=movie&sort=title&limit=50&offset=100", server.takeRequest().path)
        assertEquals("/api/music/albums?sort=year&genre=Hip%20Hop&artist=13&starred=1&limit=120&offset=0", server.takeRequest().path)
        assertEquals("/api/music/artists?limit=120&offset=0", server.takeRequest().path)
        assertEquals("/api/music/songs?starred=1&limit=120&offset=0", server.takeRequest().path)
        assertEquals("/api/books?kind=audiobook&sort=author&limit=120&offset=0", server.takeRequest().path)
        assertEquals("/api/music/shuffle?source=genre&genre=Rock", server.takeRequest().path)
        assertEquals("/api/search?q=Big%20Buck", server.takeRequest().path)
    }

    @Test
    fun channelCalls() = runTest {
        client.token = "t"
        enqueue("""{"channels":[{"id":2,"number":10,"name":"Comedy","category":"Movies","logo":1,"rules":{"order":"shuffle"}}],"canManage":true}""")
        enqueue("""{"channel":{"id":2,"number":10,"name":"Comedy"},"programs":[{"itemId":6,"title":"A","start":"2026-01-01T10:00:00Z","end":"2026-01-01T10:30:00Z"}]}""")
        val list = client.channels()
        val guide = client.channelGuide(2)
        assertEquals("Comedy", list.channels.single().name)
        assertNull(list.channels.single().now)
        assertEquals(6L, guide.programs.single().itemId)
        val first = server.takeRequest()
        assertEquals("/api/channels", first.path)
        assertEquals("Bearer t", first.getHeader("Authorization"))
        assertEquals("/api/channels/2/guide?hours=6", server.takeRequest().path)
    }

    @Test
    fun openLiveChannelSignsThePlaylistAndReportsStarting() = runTest {
        client.token = "t"
        val expires = java.time.Instant.now().plus(java.time.Duration.ofHours(12)).toString()
        enqueue("""{"key":"k1","expiresAt":"$expires"}""")
        enqueue("""{"error":"Starting"}""", code = 503, headers = mapOf("Retry-After" to "2"))
        enqueue("#EXTM3U\n")
        try {
            client.openLiveChannel(4)
            fail("503 expected")
        } catch (e: ApiException) {
            assertEquals(503, e.status)
            assertEquals(2L, e.retryAfterSec)
            assertEquals("Starting", e.message)
        }
        val url = client.openLiveChannel(4)
        assertTrue(url.endsWith("/api/channels/live/4/index.m3u8?mk=k1"))
        assertEquals("/api/auth/media-key", server.takeRequest().path)
        assertEquals("/api/channels/live/4/index.m3u8?mk=k1", server.takeRequest().path)
        // The cached key is reused for the second attempt.
        assertEquals("/api/channels/live/4/index.m3u8?mk=k1", server.takeRequest().path)
    }

    @Test
    fun lyricsNotFoundIsNull() = runTest {
        client.token = "t"
        enqueue("""{"error":"No lyrics"}""", code = 404)
        assertNull(client.lyrics(16))
    }

    @Test
    fun playlistMutations() = runTest {
        client.token = "t"
        val playlist = """{"id":1,"name":"Mix","trackCount":2,"durationMs":1,"cover":"","covers":[],"updatedAt":""}"""
        enqueue(playlist)
        enqueue(playlist)
        enqueue(code = 204)
        enqueue(code = 204)
        enqueue(code = 204)
        client.createPlaylist("Mix", listOf(15, 16))
        client.updatePlaylist(1, PlaylistPatch(order = listOf(1, 0)))
        client.deletePlaylist(1)
        client.star(14)
        client.recordPlay(15)
        val create = server.takeRequest()
        assertEquals("POST /api/music/playlists", "${create.method} ${create.path}")
        assertEquals(listOf(15, 16), takeJson(create)["trackIds"]!!.jsonArray.map { it.jsonPrimitive.int })
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals(setOf("order"), takeJson(patch).keys)
        assertEquals("DELETE /api/music/playlists/1", server.takeRequest().let { "${it.method} ${it.path}" })
        assertEquals("POST /api/music/star/14", server.takeRequest().let { "${it.method} ${it.path}" })
        val play = server.takeRequest()
        assertEquals("/api/music/plays", play.path)
        assertEquals(setOf("trackId"), takeJson(play).keys)
    }

    @Test
    fun subtitlesAreFetchedWithBearer() = runTest {
        client.token = "t"
        enqueue("WEBVTT\n\n00:00.000 --> 00:01.000\nHi\n")
        val vtt = client.subtitleVtt("/api/subs/12/1000.vtt?src=b70596c2")
        assertTrue(vtt.startsWith("WEBVTT"))
        val request = server.takeRequest()
        assertEquals("/api/subs/12/1000.vtt?src=b70596c2", request.path)
        assertEquals("Bearer t", request.getHeader("Authorization"))
    }

    @Test
    fun logoutForgetsToken() = runTest {
        client.token = "t"
        enqueue(code = 204)
        client.logout()
        assertNull(client.token)
        assertEquals("/api/logout", server.takeRequest().path)
    }

    @Test
    fun tokenIsNeverSentToAnotherHost() = runTest {
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(MockResponse().setBody("WEBVTT"))
            client.token = "tok"
            assertEquals("WEBVTT", client.subtitleVtt(other.url("/subs/1.vtt").toString()))
            assertNull(other.takeRequest().getHeader("Authorization"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun channelRoundThreeCalls() = runTest {
        repeat(3) { enqueue(code = 204) }
        enqueue("""{"from":"2026-11-11T18:30:00Z","to":"2026-11-12T00:30:00Z","channels":[]}""")
        enqueue("""{"audioLangs":["ru"],"subMode":"foreign","subLangs":["ru"],"preferCleaned":true}""")
        client.setChannelFavorite(5, true)
        client.setChannelFavorite(5, false)
        client.setLastChannel(7)
        client.channelEpg("2026-11-11T18:30:00Z", hours = 99)
        val prefs = client.playbackPrefs()

        val fav = server.takeRequest()
        assertEquals("PUT", fav.method)
        assertEquals("/api/channels/5/favorite", fav.path)
        assertEquals("external-audio", fav.getHeader("X-Vault-Caps"))
        assertEquals("DELETE", server.takeRequest().method)
        val last = server.takeRequest()
        assertEquals("/api/channels/last", last.path)
        assertEquals(7, takeJson(last)["channelId"]!!.jsonPrimitive.int)
        val epg = server.takeRequest().requestUrl!!
        assertEquals("/api/channels/epg", epg.encodedPath)
        assertEquals("2026-11-11T18:30:00Z", epg.queryParameter("from"))
        assertEquals("24", epg.queryParameter("hours"))
        assertEquals("foreign", prefs.subMode)
        assertTrue(prefs.preferCleaned)
    }
}

package app.antresol.core.channels

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelList
import app.antresol.core.model.ChannelStream
import app.antresol.core.model.PlaybackPrefs
import app.antresol.core.model.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * An external channel whose audio devices cannot decode (`remuxAudio`) is played through the
 * server's relay, which converts it to AAC; every other stream, and every stream of a server that
 * does not send the flag, is played from the provider as before. The JSON is what the server's
 * channel list carries (`channels/external-audio.json`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalAudioRelayTest {
    private val fixture: ChannelList by lazy {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("channels/external-audio.json")) { "fixture missing" }
            .bufferedReader().use { it.readText() }
        AntresolClient.json.decodeFromString<ChannelList>(text)
    }

    private fun channel(id: Long): Channel = fixture.channels.single { it.id == id }

    private class FakeApi(private val channels: List<Channel>) : LiveApi {
        val direct = mutableListOf<String>()
        val relayed = mutableListOf<String>()

        override suspend fun channels(): List<Channel> = channels

        override suspend fun guide(channelId: Long) = emptyList<app.antresol.core.model.ChannelProgram>()

        override suspend fun open(channelId: Long): String = "live/$channelId"

        override suspend fun setLast(channelId: Long) = Unit

        override suspend fun setFavorite(channelId: Long, favorite: Boolean) = Unit

        override suspend fun prefs() = PlaybackPrefs()

        override suspend fun externalUrl(url: String): String {
            direct += url
            return if (url.startsWith("/")) "http://server.example.test$url?mk=k" else url
        }

        override suspend fun relayUrl(path: String): String {
            relayed += path
            return "http://server.example.test$path"
        }
    }

    private fun TestScope.session(api: LiveApi, channelId: Long) =
        LiveTvSession(api, backgroundScope, channelId, sourceMemory = InMemoryExternalSourceMemory())

    private fun LiveTvSession.ready() = state.value.stream as LiveStream.Ready

    @Test
    fun theFlagAndTheRelayAreRead() {
        val plain = channel(100).stream!!
        assertFalse(plain.remuxAudio)
        assertEquals("/api/channels/live/ext/100/0/relay", plain.relay)
        assertFalse(plain.sources.single().viaRelay)

        val converted = channel(101).stream!!
        assertTrue(converted.remuxAudio)
        assertEquals(listOf(true, false, true), converted.sources.map { it.viaRelay })
        assertEquals(
            listOf("/api/channels/live/ext/101/0/relay", "/api/channels/live/ext/101/1/relay", "/api/channels/live/ext/101/2/relay"),
            converted.sources.map { it.relay },
        )
        // the provider's own URL stays the source's identity (the device's memory of bad sources)
        assertEquals("http://b.example.test/live/1.ts", converted.sources[0].url)
    }

    @Test
    fun anOlderServerSendsNeitherAndEverythingPlaysDirect() {
        val old = channel(102).stream!!
        assertNull(old.relay)
        assertFalse(old.remuxAudio)
        assertEquals(listOf(false, false), old.sources.map { it.viaRelay })
    }

    @Test
    fun theFlagWithoutARelayPlaysTheProviderUrl() {
        assertFalse(StreamSource("http://a.example.test/1.m3u8", remuxAudio = true).viaRelay)
        assertFalse(StreamSource("http://a.example.test/1.m3u8", relay = "", remuxAudio = true).viaRelay)
        assertFalse(StreamSource("http://a.example.test/1.m3u8", relay = "/api/channels/live/ext/1/0/relay").viaRelay)
        assertTrue(ChannelStream(url = "http://a.example.test/1.m3u8", relay = "/r", remuxAudio = true).sources.single().viaRelay)
    }

    @Test
    fun aFlaggedStreamIsPlayedThroughTheRelayWithoutTheProvidersHeaders() = runTest {
        val api = FakeApi(fixture.channels)
        val session = session(api, 101)
        session.start()
        runCurrent()
        val ready = session.ready()
        assertEquals("http://server.example.test/api/channels/live/ext/101/0/relay", ready.url)
        assertTrue(ready.external)
        assertTrue(ready.relayed)
        assertTrue(ready.viaServer)
        // the provider's headers are the relay's business, none reaches the player
        assertNull(ready.userAgent)
        assertNull(ready.referrer)
        assertEquals("ts", ready.format)
        assertEquals(3, ready.sourceCount)
        // the provider's address was never handed out, nor signed with a media key
        assertEquals(emptyList<String>(), api.direct)
        assertEquals(listOf("/api/channels/live/ext/101/0/relay"), api.relayed)
        assertEquals("http://b.example.test/live/1.ts", session.currentSourceUrl())
    }

    @Test
    fun aStreamThatIsNotFlaggedIsPlayedFromTheProviderAsBefore() = runTest {
        val api = FakeApi(fixture.channels)
        val session = session(api, 100)
        session.start()
        runCurrent()
        val ready = session.ready()
        assertEquals("http://a.example.test/plain.m3u8", ready.url)
        assertFalse(ready.relayed)
        assertFalse(ready.viaServer)
        assertEquals("UA-1", ready.userAgent)
        assertEquals(emptyList<String>(), api.relayed)
    }

    @Test
    fun anOlderServersChannelIsPlayedFromTheProvider() = runTest {
        val api = FakeApi(fixture.channels)
        val session = session(api, 102)
        session.start()
        runCurrent()
        assertEquals("http://d.example.test/old.m3u8", session.ready().url)
        assertFalse(session.ready().relayed)
        session.streamFailed()
        runCurrent()
        assertEquals("http://e.example.test/old.m3u8", session.ready().url)
        assertEquals(emptyList<String>(), api.relayed)
    }

    @Test
    fun eachSourceChoosesItsOwnWayWhenOneGivesWayToTheNext() = runTest {
        val api = FakeApi(fixture.channels)
        val session = session(api, 101)
        session.start()
        runCurrent()
        assertTrue(session.ready().relayed)
        // the relay does not open: the first fallback is not flagged, so the provider's URL
        session.streamFailed()
        runCurrent()
        assertEquals("http://c.example.test/live/1.m3u8", session.ready().url)
        assertFalse(session.ready().relayed)
        assertEquals(1, session.ready().source)
        // the last one is the server's hidden address and flagged: its relay, not the redirect
        session.streamFailed()
        runCurrent()
        assertEquals("http://server.example.test/api/channels/live/ext/101/2/relay", session.ready().url)
        assertTrue(session.ready().relayed)
        assertEquals(listOf("http://c.example.test/live/1.m3u8"), api.direct)
        assertEquals(listOf("/api/channels/live/ext/101/0/relay", "/api/channels/live/ext/101/2/relay"), api.relayed)
    }

    @Test
    fun aRelayedSourceThatPlaysSilentIsRememberedByItsProviderUrl() = runTest {
        val memory = InMemoryExternalSourceMemory()
        val api = FakeApi(fixture.channels)
        val session = LiveTvSession(api, backgroundScope, 101, sourceMemory = memory)
        session.start()
        runCurrent()
        session.streamSilent()
        runCurrent()
        assertTrue(memory.isBad(101, "http://b.example.test/live/1.ts"))
        assertEquals("http://c.example.test/live/1.m3u8", session.ready().url)
    }

    @Test
    fun theRealClientHandsOutTheRelayAsOurOwnUrlWithoutAMediaKey() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path.orEmpty().substringBefore('?')}"
                return when (request.path.orEmpty().substringBefore('?')) {
                    "/api/channels" -> MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"channels":[{"id":7,"number":7,"name":"Converted","external":true,"stream":{"url":"http://a.example.test/1.ts","relay":"/api/channels/live/ext/7/0/relay","remuxAudio":true,"format":"ts","userAgent":"UA"}}]}""",
                    )
                    "/api/me/playback-prefs" -> MockResponse().setHeader("Content-Type", "application/json").setBody("""{"audioLangs":[],"subMode":"off","subLangs":[]}""")
                    "/api/channels/last" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val client = AntresolClient(server.url("/").toString(), token = "device-token")
            val session = LiveTvSession(client.liveApi(), scope, channelId = 7, sourceMemory = InMemoryExternalSourceMemory())
            session.start()
            withTimeout(5_000) { while (session.state.value.stream !is LiveStream.Ready) delay(20) }
            val ready = session.state.value.stream as LiveStream.Ready
            assertEquals(server.url("/api/channels/live/ext/7/0/relay").toString(), ready.url)
            assertTrue(ready.relayed)
            delay(200)
            // no media key: the player's client sends the Bearer on every relay request
            assertTrue(requests.toString(), requests.none { it.contains("media-key") })
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }
}

package app.antresol.core.channels

import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import app.antresol.core.model.ChannelStream
import app.antresol.core.model.PlaybackPrefs
import app.antresol.core.model.StreamSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * External IPTV channels in the live session: played straight from the provider, the stream then
 * its fallbacks in order, a clear failure when none plays (no endless retry), zapping still works,
 * and the server's own live route is never asked for them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalChannelSessionTest {
    private class FakeApi : LiveApi {
        val channels = mutableListOf(
            Channel(id = 1, number = 1, name = "Own"),
            Channel(
                id = 100, number = 100, name = "Ext", external = true,
                stream = ChannelStream(
                    url = "http://a.example.test/1.m3u8",
                    userAgent = "UA-1",
                    referrer = "http://ref.example.test/",
                    fallbacks = listOf(
                        StreamSource("http://b.example.test/1.m3u8", userAgent = "UA-2"),
                        StreamSource("http://c.example.test/1.m3u8", unsupported = listOf("android")),
                        StreamSource("/api/channels/live/ext/100/3"),
                    ),
                ),
            ),
            Channel(id = 101, number = 101, name = "Dead", external = true, stream = ChannelStream(url = "http://dead.example.test/x")),
        )
        val opens = mutableListOf<Long>()
        val lasts = mutableListOf<Long>()
        val resolved = mutableListOf<String>()

        override suspend fun channels(): List<Channel> = channels.toList()

        override suspend fun guide(channelId: Long): List<ChannelProgram> = emptyList()

        override suspend fun open(channelId: Long): String {
            opens += channelId
            return "live/$channelId"
        }

        override suspend fun setLast(channelId: Long) {
            lasts += channelId
        }

        override suspend fun setFavorite(channelId: Long, favorite: Boolean) = Unit

        override suspend fun prefs() = PlaybackPrefs()

        override suspend fun externalUrl(url: String): String {
            resolved += url
            return if (url.startsWith("/")) "http://server.example.test$url?mk=k" else url
        }
    }

    private val api = FakeApi()

    private fun TestScope.session(
        channelId: Long,
        platform: String = LiveTvSession.PLATFORM_ANDROID,
        memory: ExternalSourceMemory = InMemoryExternalSourceMemory(),
    ) = LiveTvSession(api, backgroundScope, channelId, platform = platform, sourceMemory = memory)

    private fun LiveTvSession.ready() = state.value.stream as LiveStream.Ready

    @Test
    fun playsTheProviderStreamWithItsHeaders() = runTest {
        val session = session(100)
        session.start()
        runCurrent()
        val ready = session.ready()
        assertEquals("http://a.example.test/1.m3u8", ready.url)
        assertTrue(ready.external)
        assertEquals("UA-1", ready.userAgent)
        assertEquals("http://ref.example.test/", ready.referrer)
        assertEquals(0, ready.source)
        // Never the server's own live route for an external channel; it still becomes the last channel.
        assertEquals(emptyList<Long>(), api.opens)
        assertEquals(listOf(100L), api.lasts)
    }

    @Test
    fun fallbacksAreTriedInOrderSkippingUnsupportedOnes() = runTest {
        val session = session(100)
        session.start()
        runCurrent()
        val urls = mutableListOf(session.ready().url)
        session.streamFailed()
        runCurrent()
        assertEquals("UA-2", session.ready().userAgent)
        urls += session.ready().url
        session.streamFailed()
        runCurrent()
        urls += session.ready().url
        assertEquals(
            listOf("http://a.example.test/1.m3u8", "http://b.example.test/1.m3u8", "http://server.example.test/api/channels/live/ext/100/3?mk=k"),
            urls,
        )
        // The source marked unsupported on Android was never handed to the player.
        assertTrue(api.resolved.none { it.contains("c.example.test") })
        // None left: a clear, final failure.
        session.streamFailed()
        runCurrent()
        assertEquals(LiveStream.Failed(null, external = true), session.state.value.stream)
        advanceTimeBy(10 * 60_000)
        assertEquals(LiveStream.Failed(null, external = true), session.state.value.stream)
        assertEquals(3, api.resolved.size)
    }

    @Test
    fun theTvSkipsWhatIsUnsupportedOnAndroidTv() = runTest {
        api.channels[1] = api.channels[1].copy(
            stream = ChannelStream(url = "http://a.example.test/1.m3u8", unsupported = listOf("androidtv"), fallbacks = listOf(StreamSource("http://b.example.test/1.m3u8"))),
        )
        val tv = session(100, LiveTvSession.PLATFORM_ANDROID_TV)
        tv.start()
        runCurrent()
        assertEquals("http://b.example.test/1.m3u8", tv.ready().url)
    }

    @Test
    fun aStreamThatPlayedIsReopenedBeforeTheNextFallback() = runTest {
        val session = session(100)
        session.start()
        runCurrent()
        session.streamPlaying()
        session.streamFailed()
        advanceTimeBy(LiveTvSession.RETRY_AFTER_PLAYER_ERROR_MS + 1)
        assertEquals("http://a.example.test/1.m3u8", session.ready().url)
        // It did not play again: on to the next one.
        session.streamFailed()
        runCurrent()
        assertEquals("http://b.example.test/1.m3u8", session.ready().url)
    }

    @Test
    fun aDeadChannelFailsAndZappingStillWorks() = runTest {
        val session = session(101)
        session.start()
        runCurrent()
        session.streamFailed()
        runCurrent()
        assertEquals(LiveStream.Failed(null, external = true), session.state.value.stream)
        assertTrue(session.zap(-1))
        runCurrent()
        assertEquals(100L, session.state.value.channelId)
        assertEquals("http://a.example.test/1.m3u8", session.ready().url)
        assertTrue(session.zap(-1))
        runCurrent()
        assertEquals(LiveStream.Ready("live/1", session.ready().generation), session.state.value.stream)
        assertEquals(listOf(1L), api.opens)
    }

    @Test
    fun retryStartsOverFromThePrimaryStream() = runTest {
        val session = session(101)
        session.start()
        runCurrent()
        session.streamFailed()
        runCurrent()
        session.retry()
        runCurrent()
        assertEquals("http://dead.example.test/x", session.ready().url)
    }

    @Test
    fun aSilentSourceGivesWayToTheNextAndTheLastSilentOnePlaysWithTheNote() = runTest {
        val session = session(100)
        session.start()
        runCurrent()
        assertEquals(3, session.ready().sourceCount)
        session.streamSilent()
        runCurrent()
        assertEquals("http://b.example.test/1.m3u8", session.ready().url)
        assertEquals(1, session.ready().source)
        session.streamSilent()
        runCurrent()
        assertTrue(session.ready().viaServer)
        // the last one is silent too: it stays on, with the note
        session.streamSilent()
        runCurrent()
        assertTrue(session.ready().viaServer)
        assertTrue(session.state.value.noSupportedAudio)
    }

    @Test
    fun withNothingElseASilentPictureBeatsAFailure() = runTest {
        val session = session(100)
        session.start()
        runCurrent()
        session.streamSilent()
        runCurrent()
        // the second and third fail outright: back to the first (silent) one, with the note once it plays
        session.streamFailed()
        runCurrent()
        session.streamFailed()
        runCurrent()
        assertEquals("http://a.example.test/1.m3u8", session.ready().url)
        session.streamSilent()
        assertTrue(session.state.value.noSupportedAudio)
        // and when that one fails too, the channel fails (no loop)
        session.streamFailed()
        runCurrent()
        assertEquals(LiveStream.Failed(null, external = true), session.state.value.stream)
    }

    @Test
    fun aStalledSourceGivesWayToTheNextAndIsTriedLastNextTime() = runTest {
        val memory = InMemoryExternalSourceMemory()
        val session = session(100, memory = memory)
        session.start()
        runCurrent()
        session.streamPlaying()
        session.streamStalled()
        runCurrent()
        assertEquals("http://b.example.test/1.m3u8", session.ready().url)
        assertTrue(memory.isBad(100, "http://a.example.test/1.m3u8"))
        // the next opening of the channel starts from the good ones
        session.retry()
        runCurrent()
        assertEquals("http://b.example.test/1.m3u8", session.ready().url)
        assertEquals(0, session.ready().source)
        // with sound it loses the mark
        session.streamHasSound()
        assertTrue(!memory.isBad(100, "http://b.example.test/1.m3u8"))
    }

    @Test
    fun previousChannelGoesBackAndForth() = runTest {
        val session = session(100)
        session.start()
        runCurrent()
        assertTrue(!session.tunePrevious())
        assertTrue(session.tune(1))
        runCurrent()
        assertEquals(100L, session.state.value.previousChannelId)
        assertTrue(session.tunePrevious())
        runCurrent()
        assertEquals(100L, session.state.value.channelId)
        assertEquals(1L, session.state.value.previousChannelId)
    }
}

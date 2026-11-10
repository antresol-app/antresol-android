package app.antresol.core.channels

import app.antresol.core.api.ApiException
import app.antresol.core.model.Channel
import app.antresol.core.model.ChannelProgram
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvSessionTest {
    private class FakeApi : LiveApi {
        val channels = mutableListOf(
            Channel(id = 1, number = 1, name = "All", category = "a"),
            Channel(id = 2, number = 10, name = "Comedy", category = "b"),
            Channel(id = 3, number = 5, name = "Kids", category = "c"),
        )
        val opens = mutableListOf<Long>()
        val guides = mutableListOf<Long>()
        var channelLoads = 0

        /** Answers for the next [open] calls; empty = success. */
        val openErrors = ArrayDeque<Exception>()

        override suspend fun channels(): List<Channel> {
            channelLoads++
            return channels.toList()
        }

        override suspend fun guide(channelId: Long): List<ChannelProgram> {
            guides += channelId
            return listOf(ChannelProgram(itemId = channelId * 100, title = "On $channelId"))
        }

        override suspend fun open(channelId: Long): String {
            opens += channelId
            openErrors.removeFirstOrNull()?.let { throw it }
            return "live/$channelId"
        }
    }

    private val api = FakeApi()

    private fun TestScope.session(channelId: Long = 1) = LiveTvSession(api, backgroundScope, channelId)

    @Test
    fun opensTheStreamAndLoadsTheGuide() = runTest {
        val session = session()
        session.start()
        runCurrent()
        val state = session.state.value
        assertEquals(LiveStream.Ready("live/1", 1), state.stream)
        assertEquals(listOf(1L, 3L, 2L), state.channels.map { it.id })
        assertEquals("All", state.channel?.name)
        assertEquals(listOf(100L), state.programs.map { it.itemId })
    }

    @Test
    fun retriesWhileTheChannelStarts() = runTest {
        api.openErrors += ApiException(503, "Starting", retryAfterSec = 2)
        api.openErrors += ApiException(503, "Starting", retryAfterSec = 2)
        val session = session()
        session.start()
        runCurrent()
        assertEquals(LiveStream.Starting(1), session.state.value.stream)
        advanceTimeBy(2_001)
        assertEquals(LiveStream.Starting(2), session.state.value.stream)
        advanceTimeBy(2_001)
        assertTrue(session.state.value.stream is LiveStream.Ready)
        assertEquals(3, api.opens.size)
    }

    @Test
    fun showsTheServersRefusalAndRetriesOnRequest() = runTest {
        api.openErrors += ApiException(503, "Too many channels", retryAfterSec = 30)
        val session = session()
        session.start()
        runCurrent()
        assertEquals(LiveStream.Failed("Too many channels"), session.state.value.stream)
        advanceTimeBy(60_000)
        assertEquals(1, api.opens.size)
        session.retry()
        runCurrent()
        assertTrue(session.state.value.stream is LiveStream.Ready)
    }

    @Test
    fun zapsByNumberAndWraps() = runTest {
        val session = session(channelId = 2)
        session.start()
        runCurrent()
        assertTrue(session.zap(+1))
        runCurrent()
        assertEquals(1L, session.state.value.channelId)
        assertEquals(LiveStream.Ready("live/1", 2), session.state.value.stream)
        assertEquals(listOf(100L), session.state.value.programs.map { it.itemId })
        assertTrue(session.zap(-1))
        runCurrent()
        assertEquals(2L, session.state.value.channelId)
        assertTrue(session.zap(-1))
        runCurrent()
        assertEquals(3L, session.state.value.channelId)
        assertEquals(listOf(2L, 1L, 2L, 3L), api.opens)
    }

    @Test
    fun cannotZapWithOneChannel() = runTest {
        api.channels.retainAll { it.id == 1L }
        val session = session()
        session.start()
        runCurrent()
        assertFalse(session.zap(+1))
    }

    @Test
    fun aBrokenStreamIsOpenedAgainUntilTheBudgetRunsOut() = runTest {
        val session = session()
        session.start()
        runCurrent()
        session.streamFailed()
        assertTrue(session.state.value.stream is LiveStream.Starting)
        advanceTimeBy(LiveTvSession.RETRY_AFTER_PLAYER_ERROR_MS + 1)
        assertEquals(LiveStream.Ready("live/1", 2), session.state.value.stream)
        session.streamPlaying()
        repeat(LiveStartPolicy.MAX_ATTEMPTS) {
            session.streamFailed()
            advanceTimeBy(LiveTvSession.RETRY_AFTER_PLAYER_ERROR_MS + 1)
        }
        assertEquals(LiveStream.Failed(null), session.state.value.stream)
    }

    @Test
    fun refreshesEveryThirtySecondsUntilStopped() = runTest {
        val session = session()
        session.start()
        runCurrent()
        assertEquals(1, api.channelLoads)
        assertEquals(1, api.guides.size)
        advanceTimeBy(Channels.REFRESH_MS + 1)
        assertEquals(2, api.channelLoads)
        assertEquals(2, api.guides.size)
        session.stop()
        advanceTimeBy(Channels.REFRESH_MS * 3)
        assertEquals(2, api.channelLoads)
        session.start()
        runCurrent()
        assertEquals(3, api.channelLoads)
        assertEquals(2, api.opens.size)
    }
}

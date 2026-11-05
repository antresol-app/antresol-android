package app.antresol.core.playback

import app.antresol.core.model.PlayRequest
import app.antresol.core.model.PlayResponse
import app.antresol.core.model.PlaybackCaps
import app.antresol.core.model.Quality
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSessionTest {
    private class FakeApi : VideoApi {
        val requests = mutableListOf<PlayRequest>()
        val stopped = mutableListOf<String>()
        var next = 0

        override suspend fun play(fileId: Long, request: PlayRequest): PlayResponse {
            requests += request
            next++
            return PlayResponse(mode = "remux", url = "/api/hls/s$next/index.m3u8", startSec = (request.startSec ?: 0.0) - 1)
        }

        var keyFails = false

        override suspend fun mediaKey(minValidMs: Long) = if (keyFails) throw java.io.IOException("offline") else "key"
        override suspend fun stopSession(session: String) {
            stopped += session
        }

        override fun playbackUrl(url: String, mediaKey: String) = "http://h$url?mk=$mediaKey"
    }

    @Test
    fun reopeningReplacesTheSessionAndKeepsChoices() = runTest {
        val api = FakeApi()
        val session = VideoSession(api, fileId = 7, caps = PlaybackCaps.EXOPLAYER_BASELINE)
        val first = session.open(startMs = 0)
        assertEquals("http://h/api/hls/s1/index.m3u8?mk=key", first.url)
        assertNull(api.requests[0].startSec)
        assertNull(api.requests[0].quality)
        assertEquals("s1", session.session)

        session.open(startMs = 30_000, quality = Quality.P720, audioIndex = 2)
        assertEquals("s1", api.requests[1].replaceSession)
        assertEquals(30.0, api.requests[1].startSec!!, 0.0)
        assertEquals("720", api.requests[1].quality)
        assertEquals(2, api.requests[1].audioIndex)

        session.open(startMs = 5_000)
        assertEquals("s2", api.requests[2].replaceSession)
        assertEquals(Quality.P720, api.requests[2].quality)
        assertEquals(2, api.requests[2].audioIndex)
        assertEquals(4_000, session.source!!.timeline.startMs)
    }

    @Test
    fun aFailedMediaKeyStopsTheSessionJustOpened() = runTest {
        val api = FakeApi()
        val session = VideoSession(api, fileId = 7, caps = PlaybackCaps.EXOPLAYER_BASELINE)
        session.open(0)
        api.keyFails = true
        val failure = runCatching { session.open(30_000) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertEquals("s1", api.requests[1].replaceSession)
        assertEquals(listOf("s2"), api.stopped)
        assertNull(session.session)
    }

    @Test
    fun lostOrSuspendedSessionsAreNotReplaced() = runTest {
        val api = FakeApi()
        val session = VideoSession(api, fileId = 7, caps = PlaybackCaps.EXOPLAYER_BASELINE)
        session.open(0)
        session.sessionLost()
        session.open(0)
        assertNull(api.requests[1].replaceSession)
        session.suspendSession()
        assertEquals(listOf("s2"), api.stopped)
        assertNull(session.session)
        session.open(0)
        assertNull(api.requests[2].replaceSession)
        assertTrue(api.stopped.size == 1)
    }
}

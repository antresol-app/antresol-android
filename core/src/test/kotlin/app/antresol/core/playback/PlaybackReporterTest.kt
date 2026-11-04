package app.antresol.core.playback

import app.antresol.core.api.ApiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReporterTest {
    private class FakeApi : PlaybackApi {
        val events = mutableListOf<String>()
        var keepaliveError: Exception? = null

        override suspend fun saveProgress(itemId: Long, positionMs: Long, durationMs: Long) {
            events += "progress $itemId $positionMs/$durationMs"
        }

        override suspend fun keepalive(session: String) {
            keepaliveError?.let { throw it }
            events += "keepalive $session"
        }

        override suspend fun stopSession(session: String) {
            events += "stop $session"
        }
    }

    private var position = 0L
    private var playing = true
    private val api = FakeApi()

    private fun TestScope.reporter(onLost: (String) -> Unit = {}) = PlaybackReporter(
        api = api,
        scope = backgroundScope,
        itemId = 19,
        durationMs = 60_000,
        positionMs = { position },
        isPlaying = { playing },
        onSessionLost = onLost,
    )

    @Test
    fun writesProgressEveryTenSecondsWhenMoved() = runTest {
        val reporter = reporter()
        reporter.start()
        position = 5_000
        advanceTimeBy(10_001)
        assertEquals(listOf("progress 19 5000/60000"), api.events)

        position = 5_300 // moved less than 0.5 s: skipped
        advanceTimeBy(10_000)
        assertEquals(1, api.events.size)

        playing = false
        position = 9_000 // paused: no periodic write
        advanceTimeBy(10_000)
        assertEquals(1, api.events.size)

        reporter.flush() // pause write
        assertEquals("progress 19 9000/60000", api.events.last())
        reporter.flush() // unchanged: no duplicate
        assertEquals(2, api.events.size)
    }

    @Test
    fun keepsHlsSessionAliveAndStopsItOnExit() = runTest {
        val reporter = reporter()
        reporter.start(session = "abc")
        position = 20_000
        advanceTimeBy(20_001)
        assertEquals(listOf("keepalive abc"), api.events.filter { it.startsWith("keepalive") })
        position = 21_000
        reporter.stop()
        assertEquals("progress 19 21000/60000", api.events[api.events.size - 2])
        assertEquals("stop abc", api.events.last())
        assertNull(reporter.hlsSession)

        val before = api.events.size
        advanceTimeBy(60_000)
        reporter.stop()
        assertEquals(before, api.events.size)
    }

    @Test
    fun replacedSessionMovesKeepalive() = runTest {
        val reporter = reporter()
        reporter.start(session = "old")
        reporter.attachSession("new")
        advanceTimeBy(20_001)
        assertTrue(api.events.contains("keepalive new"))
        assertTrue(api.events.none { it == "keepalive old" })
    }

    @Test
    fun lostSessionIsReported() = runTest {
        var lost: String? = null
        val reporter = reporter { lost = it }
        api.keepaliveError = ApiException(404, "Session not found")
        reporter.start(session = "gone")
        advanceTimeBy(20_001)
        runCurrent()
        assertEquals("gone", lost)
        assertNull(reporter.hlsSession)
        reporter.stop()
        assertTrue(api.events.none { it.startsWith("stop") })
    }

    @Test
    fun positionIsClampedToDuration() = runTest {
        val reporter = reporter()
        position = 75_000
        reporter.stop()
        assertEquals(listOf("progress 19 60000/60000"), api.events)
    }
}

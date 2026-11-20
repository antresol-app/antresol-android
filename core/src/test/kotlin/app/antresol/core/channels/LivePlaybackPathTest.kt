package app.antresol.core.channels

import app.antresol.core.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live banner's playback path, codec names, the bad-source order and the fallback steps. */
class LivePlaybackPathTest {
    @Test
    fun externalPathShowsTheHostOnly() {
        val path = LivePlaybackPath.external("http://user:pw@cdn.example.test:8080/x/y?token=1", "ts", 0, 2, "H.264", "MP2")
        assertEquals("cdn.example.test", path.directHost)
        assertEquals(listOf("MPEG-TS", "H.264 / MP2"), path.technical)
        assertTrue(path.showsAttempt)
        assertEquals(1, path.attempt)
        val hidden = LivePlaybackPath.external("/api/channels/live/ext/5/1", null, 1, 2)
        assertFalse(hidden.direct)
        assertNull(hidden.format)
        assertEquals("HLS", LivePlaybackPath.formatName(null, "http://a.test/s/index.m3u8"))
        assertFalse(LivePlaybackPath.external("http://a.test/1", null, 0, 1).showsAttempt)
        assertEquals(listOf("HLS", "AAC"), LivePlaybackPath.own(audio = "AAC").technical)
    }

    @Test
    fun codecNames() {
        assertEquals("H.264", MediaCodecs.name("video/avc"))
        assertEquals("HEVC", MediaCodecs.name("video/hevc"))
        assertEquals("AAC", MediaCodecs.name("audio/mp4a-latm"))
        assertEquals("AC-3", MediaCodecs.name("audio/ac3"))
        assertEquals("MP2", MediaCodecs.name("audio/mpeg-L2"))
        assertNull(MediaCodecs.name(null))
    }

    @Test
    fun badSourcesGoLast() {
        val memory = InMemoryExternalSourceMemory()
        val a = StreamSource("http://a.test/1")
        val b = StreamSource("http://b.test/1")
        memory.markBad(7, a.url)
        assertEquals(listOf(b, a), memory.ordered(7, listOf(a, b)))
        assertEquals(listOf(a, b), memory.ordered(8, listOf(a, b)))
        memory.markGood(7, a.url)
        assertEquals(listOf(a, b), memory.ordered(7, listOf(a, b)))
    }

    @Test
    fun fallbackSteps() {
        assertEquals(ExternalFallback.Step.TryNext(1), ExternalFallback.afterFailure(0, 3, emptySet()))
        assertEquals(ExternalFallback.Step.GiveUp, ExternalFallback.afterFailure(2, 3, emptySet()))
        assertEquals(ExternalFallback.Step.AcceptSilent(0), ExternalFallback.afterFailure(2, 3, setOf(1, 0)))
        assertEquals(ExternalFallback.Step.GiveUp, ExternalFallback.afterFailure(0, 1, setOf(0)))
        assertEquals(ExternalFallback.Step.TryNext(1), ExternalFallback.afterSilence(0, 2))
        assertEquals(ExternalFallback.Step.AcceptSilent(1), ExternalFallback.afterSilence(1, 2))
    }
}

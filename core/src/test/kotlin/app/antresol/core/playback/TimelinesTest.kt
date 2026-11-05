package app.antresol.core.playback

import app.antresol.core.model.BookFile
import app.antresol.core.model.BookProgress
import app.antresol.core.model.PlayResponse
import app.antresol.core.model.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelinesTest {
    @Test
    fun hlsTimelineOffsetsByStart() {
        val timeline = StreamTimeline(PlayResponse(mode = "remux", url = "/api/hls/s/index.m3u8", startSec = 14.0))
        assertEquals(14_000, timeline.startMs)
        assertEquals(20_000, timeline.fileMs(6_000))
        assertEquals(6_000, timeline.playerMs(20_000))
        assertTrue(timeline.needsNewSession(10_000))
        assertFalse(timeline.needsNewSession(15_000))
        assertEquals(0, timeline.initialPlayerSeekMs)
        assertEquals(-14_000, timeline.subtitleOffsetMs)
    }

    @Test
    fun growingPlaylistReopensPastItsProducedPart() {
        val growing = StreamTimeline(PlayResponse(mode = "transcode", url = "/api/hls/s/index.m3u8", startSec = 10.0))
        assertFalse(growing.needsNewSession(50_000, availablePlayerMs = 60_000))
        assertTrue(growing.needsNewSession(80_000, availablePlayerMs = 60_000))
        assertFalse(growing.needsNewSession(80_000))
        val complete = StreamTimeline(
            PlayResponse(mode = "transcode", url = "/api/hls/s/index.m3u8", startSec = 10.0, hlsComplete = true),
        )
        assertFalse(complete.needsNewSession(80_000, availablePlayerMs = 60_000))
    }

    @Test
    fun directTimelineIsFileTime() {
        val timeline = StreamTimeline(PlayResponse(mode = "direct", url = "/api/stream/1", startSec = 30.0))
        assertEquals(5_000, timeline.fileMs(5_000))
        assertEquals(30_000, timeline.initialPlayerSeekMs)
        assertFalse(timeline.needsNewSession(0))
    }

    @Test
    fun audiobookPartsMapToBookTime() {
        val timeline = AudiobookTimeline(listOf(30_041, 30_000))
        assertEquals(60_041, timeline.totalMs)
        assertEquals(PartPosition(0, 10_000), timeline.locate(10_000))
        assertEquals(PartPosition(1, 0), timeline.locate(30_041))
        assertEquals(PartPosition(1, 5_000), timeline.locate(35_041))
        assertEquals(PartPosition(1, 30_000), timeline.locate(99_999))
        assertEquals(35_041, timeline.bookMs(1, 5_000))
        assertEquals(30_041, timeline.partStartMs(1))
    }

    @Test
    fun resumeRules() {
        assertEquals(0.0, Resume.videoStartSec(Progress()), 0.0)
        assertEquals(15.0, Resume.videoStartSec(Progress(positionMs = 15_000, updatedAt = "x")), 0.0)
        assertEquals(0.0, Resume.videoStartSec(Progress(positionMs = 15_000, finished = true, updatedAt = "x")), 0.0)

        assertEquals(0, Resume.audiobookStartMs(null, 600_000))
        assertEquals(120_000, Resume.audiobookStartMs(BookProgress(positionMs = 120_000), 600_000))
        assertEquals(0, Resume.audiobookStartMs(BookProgress(positionMs = 590_000), 600_000))
        assertEquals(0, Resume.audiobookStartMs(BookProgress(positionMs = 120_000, finished = true), 600_000))
    }

    @Test
    fun scrobbleOncePerTrack() {
        val short = ScrobbleTracker(durationMs = 20_000)
        assertFalse(short.onPlayed(9_000))
        assertTrue(short.onPlayed(1_000))
        assertFalse(short.onPlayed(10_000))

        val long = ScrobbleTracker(durationMs = 300_000)
        assertFalse(long.onPlayed(29_999))
        assertTrue(long.onPlayed(1))
    }

    @Test
    fun audiobookWithUnknownDurationsStartsAtTheFirstPart() {
        val unknown = AudiobookTimeline(listOf(0, 0))
        assertFalse(unknown.isComplete)
        assertEquals(PartPosition(0, 0), unknown.locate(120_000))
        val single = AudiobookTimeline.of(listOf(BookFile(id = 1)), bookDurationMs = 60_000)
        assertTrue(single.isComplete)
        assertEquals(PartPosition(0, 30_000), single.locate(30_000))
    }
}

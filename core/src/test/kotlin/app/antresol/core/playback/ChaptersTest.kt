package app.antresol.core.playback

import app.antresol.core.model.Chapter
import app.antresol.core.model.LyricLine
import app.antresol.core.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChaptersTest {
    private val chapters = listOf(
        Chapter(idx = 0, title = "One", startMs = 0, endMs = 10_000),
        Chapter(idx = 1, title = "Two", startMs = 10_000, endMs = 20_000),
        Chapter(idx = 2, title = "Three", startMs = 20_000, endMs = 0),
    )

    @Test
    fun indexAtFindsTheChapterPlaying() {
        assertEquals(0, Chapters.indexAt(chapters, 0))
        assertEquals(0, Chapters.indexAt(chapters, 9_999))
        assertEquals(1, Chapters.indexAt(chapters, 10_000))
        assertEquals(2, Chapters.indexAt(chapters, 99_000))
        assertEquals(-1, Chapters.indexAt(emptyList(), 5_000))
    }

    @Test
    fun previousRestartsTheChapterUnlessNearItsStart() {
        assertEquals(10_000, Chapters.previousStartMs(chapters, 15_000))
        assertEquals(0, Chapters.previousStartMs(chapters, 11_000))
        assertEquals(0, Chapters.previousStartMs(chapters, 1_000))
        assertEquals(0, Chapters.previousStartMs(emptyList(), 50_000))
    }

    @Test
    fun nextIsNullInTheLastChapter() {
        assertEquals(10_000L, Chapters.nextStartMs(chapters, 0))
        assertEquals(20_000L, Chapters.nextStartMs(chapters, 12_000))
        assertNull(Chapters.nextStartMs(chapters, 25_000))
    }

    @Test
    fun endFallsBackToTheBookDuration() {
        assertEquals(10_000, Chapters.endMs(chapters, 5_000, 30_000))
        assertEquals(30_000, Chapters.endMs(chapters, 25_000, 30_000))
        assertEquals(30_000, Chapters.endMs(emptyList(), 25_000, 30_000))
    }

    @Test
    fun syncedLyricLineFollowsThePosition() {
        val lyrics = Lyrics(synced = true, lines = listOf(LyricLine(1.0, "a"), LyricLine(4.5, "b"), LyricLine(9.0, "c")))
        assertEquals(-1, lyrics.lineIndexAt(500))
        assertEquals(0, lyrics.lineIndexAt(1_000))
        assertEquals(1, lyrics.lineIndexAt(8_999))
        assertEquals(2, lyrics.lineIndexAt(60_000))
        assertEquals(-1, lyrics.copy(synced = false).lineIndexAt(5_000))
        assertEquals("a\nb\nc", lyrics.plainText)
        assertEquals("plain", Lyrics(lyrics = "plain").plainText)
    }
}

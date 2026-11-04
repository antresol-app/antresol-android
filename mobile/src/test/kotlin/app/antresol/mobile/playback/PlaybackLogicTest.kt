package app.antresol.mobile.playback

import app.antresol.core.model.BookDetail
import app.antresol.core.model.BookFile
import app.antresol.core.model.BookProgress
import app.antresol.core.model.Card
import app.antresol.core.model.Chapter
import app.antresol.core.model.Kind
import app.antresol.core.model.VolumeCard
import app.antresol.mobile.reader.ReaderProgress
import app.antresol.mobile.ui.detail.BookAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackLogicTest {
    @Test
    fun mediaRefsRoundTrip() {
        val refs = listOf(MediaRef.Track(42), MediaRef.BookPart(bookId = 23, partIndex = 1, fileId = 77))
        refs.forEach { assertEquals(it, MediaRef.parse(it.encode())) }
        assertEquals("track:42", MediaRef.Track(42).encode())
        assertNull(MediaRef.parse(null))
        assertNull(MediaRef.parse("book:1:2"))
        assertNull(MediaRef.parse("track:x"))
        assertNull(MediaRef.parse("video:1"))
    }

    @Test
    fun chapterAtPicksTheChapterContainingThePosition() {
        val chapters = listOf(Chapter(0, "One", startMs = 0, endMs = 30_000), Chapter(1, "Two", startMs = 30_041, endMs = 60_000))
        assertEquals("One", PlaybackConnection.chapterAt(chapters, 0)?.title)
        assertEquals("One", PlaybackConnection.chapterAt(chapters, 30_040)?.title)
        assertEquals("Two", PlaybackConnection.chapterAt(chapters, 30_041)?.title)
        assertEquals("Two", PlaybackConnection.chapterAt(chapters, 90_000)?.title)
        assertNull(PlaybackConnection.chapterAt(emptyList(), 5))
    }

    @Test
    fun readerLocatorsFollowTheSpec() {
        assertEquals("page:1", ReaderProgress.locator(0))
        assertEquals(0.25, ReaderProgress.fraction(0, 4), 1e-9)
        assertEquals(1.0, ReaderProgress.fraction(3, 4), 1e-9)
        assertEquals(4, ReaderProgress.startPage("page:5", finished = false, count = 10))
        assertEquals(9, ReaderProgress.startPage("page:50", finished = false, count = 10))
        assertEquals(0, ReaderProgress.startPage("page:5", finished = true, count = 10))
        assertEquals(0, ReaderProgress.startPage("epubcfi(/6/4)", finished = false, count = 10))
        assertEquals(0, ReaderProgress.startPage(null, finished = false, count = 10))
    }

    @Test
    fun audiobookActionResumesUnlessNearlyDone() {
        val parts = listOf(BookFile(1, "MP3", durationMs = 60_000), BookFile(2, "MP3", durationMs = 60_000))
        fun book(progress: BookProgress?) = BookDetail(Card(10, Kind.AUDIOBOOK), files = parts, durationMs = 120_000, progress = progress)
        assertEquals(BookAction.Listen(0, resume = false), BookAction.of(book(null)))
        assertEquals(BookAction.Listen(45_000, resume = true), BookAction.of(book(BookProgress(positionMs = 45_000))))
        // Within the last 30 s: start over (spec section 11).
        assertEquals(BookAction.Listen(0, resume = false), BookAction.of(book(BookProgress(positionMs = 100_000))))
        assertNull(BookAction.of(BookDetail(Card(11, Kind.AUDIOBOOK))))
    }

    @Test
    fun comicSeriesContinuesTheVolumeBeingRead() {
        fun series(vararg progress: Double) = BookDetail(
            Card(20, Kind.COMIC),
            volumes = progress.mapIndexed { i, p -> VolumeCard(id = 100L + i, progress = p, fileId = 200L + i) },
        )
        assertEquals(BookAction.ReadComic(101, resume = true), BookAction.of(series(1.0, 0.4, 0.0)))
        assertEquals(BookAction.ReadComic(101, resume = false), BookAction.of(series(1.0, 0.0)))
        assertEquals(BookAction.ReadComic(100, resume = false), BookAction.of(series(1.0, 1.0)))
        assertNull(BookAction.of(series()))
        assertEquals(
            BookAction.ReadComic(21, resume = true),
            BookAction.of(BookDetail(Card(21, Kind.COMIC_VOLUME), progress = BookProgress(fraction = 0.5, locator = "page:3"))),
        )
    }

    @Test
    fun booksOpenOnlyAsPdf() {
        assertEquals(BookAction.ReadPdf(30, resume = false), BookAction.of(BookDetail(Card(30, Kind.BOOK), files = listOf(BookFile(5, "pdf")))))
        assertEquals(BookAction.Unsupported("EPUB"), BookAction.of(BookDetail(Card(31, Kind.BOOK), files = listOf(BookFile(6, "epub")))))
        assertNull(BookAction.of(BookDetail(Card(32, Kind.BOOK))))
    }
}

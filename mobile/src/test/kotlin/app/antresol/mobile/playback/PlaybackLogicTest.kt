package app.antresol.mobile.playback

import app.antresol.core.model.BookDetail
import app.antresol.core.model.BookFile
import app.antresol.core.model.BookProgress
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.VolumeCard
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

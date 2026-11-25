package app.antresol.core.playback

import app.antresol.core.model.LyricLine
import app.antresol.core.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRowsTest {
    private fun synced(vararg lines: Pair<Double, String>) =
        Lyrics(synced = true, lines = lines.map { (t, text) -> LyricLine(t, text) })

    @Test
    fun anEmptyFirstLineIsTheIntroAndIsNotShown() {
        val rows = synced(0.0 to "", 3.0 to "First", 6.0 to "Second").rows()
        assertEquals(listOf("First", "Second"), rows.map { it.text })
        assertFalse(rows.any { it.instrumental })
    }

    @Test
    fun instrumentalLinesInARowAreOneRow() {
        val rows = synced(0.0 to "A", 3.0 to "♪", 6.0 to "", 9.0 to " ♪ ♪ ", 12.0 to "...", 15.0 to "B").rows()
        assertEquals(listOf("A", "", "B"), rows.map { it.text })
        assertEquals(listOf(false, true, false), rows.map { it.instrumental })
        // The stretch starts where its first line did.
        assertEquals(3.0, rows[1].startSec, 0.0)
        assertEquals(1, rows[1].from)
    }

    @Test
    fun aSingleInstrumentalBreakBetweenSungLinesStays() {
        val rows = synced(0.0 to "A", 3.0 to "", 6.0 to "B").rows()
        assertEquals(listOf(false, true, false), rows.map { it.instrumental })
    }

    @Test
    fun theOutroIsNotShownEither() {
        val rows = synced(0.0 to "A", 3.0 to "B", 6.0 to "♪", 9.0 to "").rows()
        assertEquals(listOf("A", "B"), rows.map { it.text })
    }

    @Test
    fun aSongOfNothingButBreaksHasNoRows() {
        assertTrue(synced(0.0 to "", 3.0 to "♪").rows().isEmpty())
    }

    @Test
    fun aLineWithAnyWordsIsSungEvenWhenItLooksLikeAMarker() {
        val rows = synced(0.0 to "(la la)", 3.0 to "42", 6.0 to "♪ La ♪").rows()
        assertEquals(listOf("(la la)", "42", "♪ La ♪"), rows.map { it.text })
    }

    @Test
    fun theCurrentRowFollowsThePositionAndHoldsInTheOutro() {
        val lyrics = synced(0.0 to "", 3.0 to "A", 6.0 to "♪", 9.0 to "", 12.0 to "B", 15.0 to "")
        val rows = lyrics.rows()
        assertEquals(3, rows.size)
        // The intro is before every row.
        assertEquals(-1, lyrics.rowIndexAt(rows, 1_000))
        assertEquals(0, lyrics.rowIndexAt(rows, 3_000))
        assertEquals(0, lyrics.rowIndexAt(rows, 5_999))
        // Both lines of the break are the one instrumental row.
        assertEquals(1, lyrics.rowIndexAt(rows, 6_000))
        assertEquals(1, lyrics.rowIndexAt(rows, 11_999))
        assertEquals(2, lyrics.rowIndexAt(rows, 12_000))
        // The empty last line is not a row: B stays current.
        assertEquals(2, lyrics.rowIndexAt(rows, 20_000))
    }

    @Test
    fun plainLyricsKeepTheirStanzasWithoutEmptyRows() {
        val rows = Lyrics(lyrics = "\n\nOne\nTwo\n\n\n  \nThree\n\n").rows()
        assertEquals(listOf("One", "Two", "Three"), rows.map { it.text })
        assertEquals(listOf(false, false, true), rows.map { it.startsStanza })
        assertTrue(rows.none { it.instrumental })
    }

    @Test
    fun plainLyricsHaveNoCurrentRow() {
        val lyrics = Lyrics(lyrics = "One\nTwo")
        assertEquals(-1, lyrics.rowIndexAt(lyrics.rows(), 5_000))
    }

    @Test
    fun syncedLinesWithoutTimesFallBackToTheText() {
        val rows = Lyrics(lyrics = "One\n\nTwo", synced = true, lines = emptyList()).rows()
        assertEquals(listOf("One", "Two"), rows.map { it.text })
    }
}

package app.antresol.core.playback

import app.antresol.core.model.Lyrics

/** The synced lyric line sung at [positionMs] (lines are in seconds), or -1 before the first or when unsynced. */
fun Lyrics.lineIndexAt(positionMs: Long): Int {
    if (!synced || lines.isEmpty()) return -1
    val seconds = positionMs / 1000.0
    return lines.indexOfLast { it.t <= seconds }
}

/** Plain lyric text: the unsynced text, or the synced lines joined. */
val Lyrics.plainText: String
    get() = lyrics.ifBlank { lines.joinToString("\n") { it.text } }

/**
 * One line of the lyrics as the screens show it: a line of words, or the one «♪» of an instrumental stretch
 * ([instrumental], [text] empty: the screen draws the note itself).
 */
data class LyricRow(
    val text: String,
    val instrumental: Boolean = false,
    /** The first of the file's lines this row stands for: an instrumental row stands for every one of its stretch. */
    val from: Int = 0,
    /** Seconds: when the row starts. 0 for unsynced lyrics. */
    val startSec: Double = 0.0,
    /** A plain lyric's blank line came before this one: the screen opens a little space above it. */
    val startsStanza: Boolean = false,
)

/**
 * The lines to draw. An empty line, or one of no words at all (a «♪», «...», «-»), is an instrumental stretch;
 * stretches that follow each other are one row, and one before the first sung line or after the last is none
 * (the intro and the outro of the song are not lyrics). Plain lyrics have no stretches: a blank line there only
 * parts the stanzas ([LyricRow.startsStanza]), so no row is ever empty.
 */
fun Lyrics.rows(): List<LyricRow> {
    if (!synced || lines.isEmpty()) return plainRows()
    val out = ArrayList<LyricRow>()
    lines.forEachIndexed { index, line ->
        val text = line.text.trim()
        val instrumental = text.none { it.isLetterOrDigit() }
        if (instrumental && out.lastOrNull()?.instrumental == true) return@forEachIndexed
        out += LyricRow(text = if (instrumental) "" else text, instrumental = instrumental, from = index, startSec = line.t)
    }
    while (out.lastOrNull()?.instrumental == true) out.removeAt(out.lastIndex)
    return out.dropWhile { it.instrumental }
}

private fun Lyrics.plainRows(): List<LyricRow> {
    val out = ArrayList<LyricRow>()
    var stanzaBreak = false
    plainText.lines().forEach { raw ->
        val text = raw.trim()
        if (text.isEmpty()) {
            stanzaBreak = out.isNotEmpty()
        } else {
            out += LyricRow(text = text, startsStanza = stanzaBreak)
            stanzaBreak = false
        }
    }
    return out
}

/**
 * The index in [rows] (the result of [rows]) of the row sung at [positionMs]: -1 in the intro and for unsynced
 * lyrics. In the outro the last row stays the current one.
 */
fun Lyrics.rowIndexAt(rows: List<LyricRow>, positionMs: Long): Int {
    val line = lineIndexAt(positionMs)
    return if (line < 0) -1 else rows.indexOfLast { it.from <= line }
}

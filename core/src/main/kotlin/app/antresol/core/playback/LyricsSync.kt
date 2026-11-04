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

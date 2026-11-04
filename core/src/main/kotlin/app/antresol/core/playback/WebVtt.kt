package app.antresol.core.playback

import java.util.Locale

/**
 * WebVTT helpers for side-loaded subtitles. The server's cue times are file times; when an HLS
 * session starts at `startSec` the cues must move by `-startSec` to line up with player time.
 */
object WebVtt {
    private val TIMING = Regex("""^(\s*)(\S+)(\s+-->\s+)(\S+)(.*)$""")

    /**
     * Shifts every cue by [offsetMs] (negative moves cues earlier). Cues that end before 0 are
     * dropped and cues that straddle 0 start at 0. Headers, styles and notes are kept as they are.
     */
    fun shift(vtt: String, offsetMs: Long): String {
        if (offsetMs == 0L) return vtt
        val blocks = vtt.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}"))
        val out = ArrayList<String>(blocks.size)
        for (block in blocks) {
            val lines = block.split('\n')
            val timingLine = lines.indexOfFirst { it.contains("-->") }
            val match = if (timingLine >= 0) TIMING.find(lines[timingLine]) else null
            if (match == null) {
                out += block
                continue
            }
            val (lead, startText, arrow, endText, settings) = match.destructured
            val start = parseTimestamp(startText)
            val end = parseTimestamp(endText)
            if (start == null || end == null) {
                out += block
                continue
            }
            val newEnd = end + offsetMs
            if (newEnd <= 0) continue
            val newStart = (start + offsetMs).coerceAtLeast(0)
            val shifted = lead + formatTimestamp(newStart) + arrow + formatTimestamp(newEnd) + settings
            out += lines.toMutableList().also { it[timingLine] = shifted }.joinToString("\n")
        }
        return out.joinToString("\n\n")
    }

    /** Parses `hh:mm:ss.ttt` or `mm:ss.ttt` into milliseconds. */
    fun parseTimestamp(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return null
        val secParts = parts.last().split('.', ',')
        val seconds = secParts[0].toLongOrNull() ?: return null
        val millis = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    fun formatTimestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        return String.format(
            Locale.ROOT, "%02d:%02d:%02d.%03d",
            total / 3_600_000, (total / 60_000) % 60, (total / 1000) % 60, total % 1000,
        )
    }
}

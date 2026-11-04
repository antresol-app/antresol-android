package app.antresol.core.playback

import app.antresol.core.model.BookFile
import app.antresol.core.model.BookProgress
import app.antresol.core.model.PlayResponse
import app.antresol.core.model.Progress

/**
 * Maps between player time and file time for a `/api/play` answer. For HLS, player time 0 is
 * [PlayResponse.startSec] in the file; for direct play the player position is the file position.
 */
class StreamTimeline(private val play: PlayResponse) {
    val startMs: Long = if (play.isHls) (play.startSec * 1000).toLong() else 0L

    fun fileMs(playerMs: Long): Long = startMs + playerMs

    fun playerMs(fileMs: Long): Long = fileMs - startMs

    /** Seeking before the HLS start needs a new `/api/play` with `replaceSession`. */
    fun needsNewSession(targetFileMs: Long): Boolean = play.isHls && targetFileMs < startMs

    /** Where the player must seek right after loading: direct play starts at 0 and seeks to the resume point. */
    val initialPlayerSeekMs: Long = if (play.isHls) 0L else (play.startSec * 1000).toLong()

    /** Subtitle cue times are file times; shift them by this to get player times. */
    val subtitleOffsetMs: Long get() = -startMs
}

/** A position inside one part of a multi-file audiobook. */
data class PartPosition(val partIndex: Int, val offsetMs: Long)

/** The whole-book timeline of an audiobook made of ordered parts. */
class AudiobookTimeline(partDurationsMs: List<Long>) {
    private val durations = partDurationsMs.map { it.coerceAtLeast(0) }
    private val starts: List<Long> = durations.runningFold(0L) { acc, d -> acc + d }.dropLast(1)

    val totalMs: Long = durations.sum()
    val partCount: Int get() = durations.size

    fun partStartMs(index: Int): Long = starts[index]

    /** The part containing [bookMs] and the offset inside it. Positions past the end land at the end of the last part. */
    fun locate(bookMs: Long): PartPosition {
        require(durations.isNotEmpty()) { "Audiobook has no parts" }
        val clamped = bookMs.coerceIn(0, totalMs)
        for (i in durations.indices) {
            if (clamped < starts[i] + durations[i]) return PartPosition(i, clamped - starts[i])
        }
        return PartPosition(durations.lastIndex, durations.last())
    }

    fun bookMs(partIndex: Int, offsetMs: Long): Long = starts[partIndex] + offsetMs.coerceIn(0, durations[partIndex])

    companion object {
        fun of(files: List<BookFile>): AudiobookTimeline = AudiobookTimeline(files.map { it.durationMs })
    }
}

/** Resume rules from the spec (section 11). */
object Resume {
    /** Audiobooks restart from 0 when finished, at >= 98 %, or with <= 30 s left. */
    const val AUDIOBOOK_TAIL_MS = 30_000L
    const val AUDIOBOOK_DONE_FRACTION = 0.98

    /** Video: resume at the saved position unless finished. Returns seconds for `PlayRequest.startSec`. */
    fun videoStartSec(progress: Progress): Double =
        if (!progress.hasProgress || progress.finished) 0.0 else progress.positionMs / 1000.0

    /** Audiobook: the whole-book position to resume from. */
    fun audiobookStartMs(progress: BookProgress?, durationMs: Long): Long {
        if (progress == null || progress.finished) return 0
        val position = progress.positionMs
        if (durationMs > 0 && (position >= AUDIOBOOK_DONE_FRACTION * durationMs || durationMs - position <= AUDIOBOOK_TAIL_MS)) {
            return 0
        }
        return position.coerceAtLeast(0)
    }
}

/**
 * Decides when a track counts as played: after min(30 s, duration / 2) of actually played time,
 * once per track (spec section 11, music step 4).
 */
class ScrobbleTracker(durationMs: Long) {
    private val thresholdMs: Long = minOf(30_000L, durationMs / 2).coerceAtLeast(1)
    private var playedMs = 0L
    private var reported = false

    /** Adds played time; returns true exactly once, when the threshold is first reached. */
    fun onPlayed(deltaMs: Long): Boolean {
        if (reported || deltaMs <= 0) return false
        playedMs += deltaMs
        if (playedMs >= thresholdMs) {
            reported = true
            return true
        }
        return false
    }
}

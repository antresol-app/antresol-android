package app.antresol.core.playback

import app.antresol.core.model.AutoSkip
import app.antresol.core.model.PlaySegment

/**
 * Skip segments of a file (`/api/play` `segments`): the recap, the intro and the credits, in seconds
 * on the file timeline. The player offers «Пропустить пересказ / вступление» while playback is inside
 * one, and «Следующая серия» (filling over 10 s of playing, then playing it) at the credits. The rules are the
 * web player's (`skipSegments.ts`, `SkipControls.tsx`).
 */
object SkipSegments {
    const val RECAP = "recap"
    const val INTRO = "intro"
    const val CREDITS = "credits"
    private val KINDS = setOf(RECAP, INTRO, CREDITS)

    /** Shorter segments are not offered (a stray chapter mark). */
    const val MIN_SEGMENT_SEC = 3.0

    /** The pill shows this long after playback enters a segment, even with the controls hidden. */
    const val PILL_ALONE_MS = 8_000L

    /** «Следующая серия» fills over this long of playing, then plays it. */
    const val NEXT_COUNTDOWN_SEC = 10.0

    /** A gap between two position ticks longer than this is a stall, not playback: it counts for no more than this. */
    const val MAX_TICK_GAP_MS = 1_500L

    /** Credits ending this long before the file does leave a scene after them: no autoplay. */
    const val POST_CREDITS_SEC = 20.0

    /** The note after an automatic skip («Вступление пропущено · Вернуть») stays this long. */
    const val SKIPPED_NOTE_MS = 6_000L

    /** The segments the player uses: known kinds, at least [MIN_SEGMENT_SEC], in time order. */
    fun usable(segments: List<PlaySegment>): List<PlaySegment> = segments
        .filter { it.kind in KINDS && it.startSec.isFinite() && it.endSec.isFinite() && it.endSec - it.startSec >= MIN_SEGMENT_SEC }
        .sortedBy { it.startSec }

    /** The segment playback is in at [timeSec] (its last quarter second counts as out: a seek to its end lands there). */
    fun at(segments: List<PlaySegment>, timeSec: Double): PlaySegment? =
        segments.firstOrNull { timeSec >= it.startSec && timeSec < it.endSec - 0.25 }

    /** A scene follows the credits: they end well before the file does. */
    fun postCreditsScene(credits: PlaySegment, durationSec: Double): Boolean =
        durationSec > 0 && durationSec - credits.endSec > POST_CREDITS_SEC

    /** The credits run to the end of the file: skipping them ends it. */
    fun creditsToEnd(credits: PlaySegment, durationSec: Double): Boolean =
        durationSec <= 0 || durationSec - credits.endSec < 1

    /**
     * How far «Следующая серия» has filled (0…1) after [playedMs] of playback in the credits. Playing time,
     * not file position: a seek forward inside the credits does not fill it.
     */
    fun countdown(playedMs: Long): Double = (playedMs / 1000.0 / NEXT_COUNTDOWN_SEC).coerceIn(0.0, 1.0)
}

/** What a skip pill (or the player by itself) does. */
sealed interface SkipAction {
    /** Seek to [toMs] on the file timeline. */
    data class Seek(val toMs: Long) : SkipAction

    /** On to the next episode. */
    data object Next : SkipAction

    /** The credits run to the end and are skipped: as if the file ended. */
    data object End : SkipAction
}

/** The pill over the video for the segment playback is in. */
sealed interface SkipPill {
    /**
     * «Пропустить пересказ / вступление / титры» ([kind]); [alone]: within [SkipSegments.PILL_ALONE_MS]
     * of the segment's start, when it shows with the controls hidden too (later only with them).
     */
    data class Skip(val kind: String, val alone: Boolean, val action: SkipAction) : SkipPill

    /**
     * «Следующая серия» beside «Смотреть титры» at the credits of an episode with a next one. With
     * [countdown] it fills ([fill], 0…1) and then plays it; never when a scene follows the credits.
     */
    data class Next(val fill: Float, val countdown: Boolean) : SkipPill
}

/** A recap or intro the player skipped by itself; «Вернуть» seeks back to [backMs]. */
data class SkipNote(val kind: String, val backMs: Long)

/**
 * The skip state of one file: which pill shows at a position, and what «Пропускать сам» does by
 * itself, once per segment (a segment skipped by itself, or taken back, is never skipped again in
 * this file). The player calls [step] on its position ticks and runs [Step.auto].
 */
class SkipTracker(segments: List<PlaySegment>) {
    val segments: List<PlaySegment> = SkipSegments.usable(segments)

    /** The file has a usable segment (the «Пропускать сам» menu shows only then). */
    val hasSegments: Boolean get() = segments.isNotEmpty()

    /** «Смотреть титры» was chosen: no «Следующая серия» and no automatic next for this file. */
    var creditsDismissed: Boolean = false

    private val autoDone = HashSet<String>()
    private var entered: Entered? = null
    private var nexted = false

    /** Playing time spent in the segment entered (what fills «Следующая серия»), and the clock of the last playing tick. */
    private var playedMs = 0L
    private var lastPlayingMs: Long? = null

    private data class Entered(val key: String, val ms: Long)

    /** What shows ([pill]) and what the player does by itself now ([auto], at most once). */
    data class Step(val pill: SkipPill?, val auto: Auto?)

    sealed interface Auto {
        /** A recap or intro skipped by itself: seek to [toMs] and show [note]. */
        data class Skipped(val toMs: Long, val note: SkipNote) : Auto

        data object Next : Auto

        data object End : Auto
    }

    /**
     * The state at [timeSec] of the file ([nowMs]: a monotonic clock for the pill's 8 s and for the
     * countdown's playing time, which counts only ticks with [playing]). [durationSec] is the file's
     * (0: unknown), [hasNext]: an episode follows, [autoplayNext]: the device plays it after a
     * countdown, [autoSkip]: the profile's «Пропускать сам».
     */
    fun step(
        timeSec: Double,
        nowMs: Long,
        playing: Boolean,
        durationSec: Double,
        hasNext: Boolean,
        autoplayNext: Boolean,
        autoSkip: AutoSkip,
    ): Step {
        val active = SkipSegments.at(segments, timeSec)
        val key = active?.let { "${it.kind}:${it.startSec}" }
        if (key == null) {
            entered = null
            lastPlayingMs = null
        } else if (entered?.key != key) {
            entered = Entered(key, nowMs)
            playedMs = 0
            lastPlayingMs = null
        }
        if (active == null || key == null) return Step(null, null)

        // Real playback time since entry: a seek inside the segment moves the position, not this.
        if (playing) {
            lastPlayingMs?.let { playedMs += (nowMs - it).coerceIn(0, SkipSegments.MAX_TICK_GAP_MS) }
            lastPlayingMs = nowMs
        } else {
            lastPlayingMs = null
        }

        var auto: Auto? = null
        if (playing && key !in autoDone) {
            val kind = active.kind
            if ((kind == SkipSegments.RECAP && autoSkip.recap) || (kind == SkipSegments.INTRO && autoSkip.intro)) {
                autoDone += key
                return Step(null, Auto.Skipped(ms(active.endSec), SkipNote(kind, ms(active.startSec))))
            }
            if (kind == SkipSegments.CREDITS && autoSkip.credits && !creditsDismissed && !SkipSegments.postCreditsScene(active, durationSec)) {
                autoDone += key
                auto = if (hasNext) next() else Auto.End
            }
        }

        val enteredAt = entered!!
        val alone = nowMs - enteredAt.ms < SkipSegments.PILL_ALONE_MS
        val pill: SkipPill = when (active.kind) {
            SkipSegments.CREDITS -> if (hasNext && !creditsDismissed) {
                val countdown = autoplayNext && !SkipSegments.postCreditsScene(active, durationSec)
                val fill = if (countdown) SkipSegments.countdown(playedMs) else 0.0
                if (countdown && fill >= 1.0 && auto == null) auto = next()
                SkipPill.Next(fill.toFloat(), countdown)
            } else if (!hasNext) {
                val action = if (SkipSegments.creditsToEnd(active, durationSec)) SkipAction.End else SkipAction.Seek(ms(active.endSec))
                SkipPill.Skip(active.kind, alone, action)
            } else {
                return Step(null, auto)
            }
            else -> SkipPill.Skip(active.kind, alone, SkipAction.Seek(ms(active.endSec)))
        }
        return Step(pill, auto)
    }

    /** «Следующая серия» pressed or the countdown ran out: true the first time only. */
    fun takeNext(): Boolean {
        if (nexted) return false
        nexted = true
        return true
    }

    private fun next(): Auto? = if (takeNext()) Auto.Next else null

    private fun ms(sec: Double): Long = (sec * 1000).toLong()
}

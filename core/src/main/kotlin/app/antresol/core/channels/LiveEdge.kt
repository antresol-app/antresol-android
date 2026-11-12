package app.antresol.core.channels

/**
 * Where the live player stands against the live edge, in milliseconds of the stream's window (the
 * same numbers for Antresol's channels and external ones; the player reads them from ExoPlayer).
 *
 * - A channel may be paused. It comes back where it stopped only while the stream still holds that
 *   picture: Antresol's channels keep about a minute ([OWN_WINDOW_MS]); an external stream keeps
 *   its seekable window. A longer pause resumes at the live edge.
 * - More than [BEHIND_LIVE_MS] behind the edge, «В эфир» shows (and jumps to the edge); at the edge
 *   «Прямой эфир» does.
 * - A stream that is not live (a provider's recording with an end) has no edge: no «В эфир», and a
 *   pause resumes where it stopped.
 */
data class LiveClock(
    /** A live stream (a playlist without an end). */
    val live: Boolean,
    /** The playing position. */
    val positionMs: Long,
    /** The live edge: where playback sits when it is live (ExoPlayer's default position). */
    val edgeMs: Long,
    /** The oldest position the stream still holds (the window's start). */
    val startMs: Long = 0,
) {
    companion object {
        val NotLive = LiveClock(live = false, positionMs = 0, edgeMs = 0)
    }
}

object LiveEdge {
    /** Behind the edge by more than this, the picture is not «live» any more. */
    const val BEHIND_LIVE_MS = 10_000L

    /** What Antresol's live playlists hold behind the edge (10 segments of 6 s). */
    const val OWN_WINDOW_MS = 60_000L

    /** The controls (and the centre play/pause) hide after this long without a touch or a key. */
    const val CONTROLS_HIDE_MS = 4_000L

    /** How far the picture is behind the edge (0 at it, or for a stream that is not live). */
    fun behind(clock: LiveClock): Long = if (!clock.live) 0 else (clock.edgeMs - clock.positionMs).coerceAtLeast(0)

    /** «В эфир» shows: live and more than [BEHIND_LIVE_MS] behind the edge. */
    fun showGoLive(clock: LiveClock): Boolean = clock.live && behind(clock) > BEHIND_LIVE_MS

    /** How far back the stream holds the picture: a minute on our channels, the window on an external one. */
    fun window(own: Boolean, clock: LiveClock): Long = if (own) OWN_WINDOW_MS else (clock.edgeMs - clock.startMs).coerceAtLeast(0)

    /**
     * A resume after a pause starts at the live edge (true) when the stopped picture is gone: the
     * pause plus how far behind it already was outlasts [window], or the position fell out of the
     * stream. Otherwise it goes on from where it stopped.
     */
    fun resumeAtEdge(own: Boolean, pausedMs: Long, behindAtPauseMs: Long, clock: LiveClock): Boolean {
        if (!clock.live) return false
        val gone = pausedMs + behindAtPauseMs.coerceAtLeast(0) >= window(own, clock)
        return gone || clock.positionMs < clock.startMs
    }

    /**
     * The centre play/pause on the picture: once the channel plays (the spinner or the message has
     * the centre before that), with the controls, and always while paused.
     */
    fun centerShown(controls: Boolean, paused: Boolean, ready: Boolean): Boolean = ready && (controls || paused)
}

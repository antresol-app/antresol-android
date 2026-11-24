package app.antresol.ui.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Calls [tick] every [periodMs] on [scope] (at once, then after each period) while it is on. A loop
 * that lives as long as its view model wakes the main thread twice a second also while the video
 * is paused or the app is in the background, so the players switch it with [set].
 */
internal class Ticker(
    private val scope: CoroutineScope,
    private val periodMs: Long,
    private val tick: () -> Unit,
) {
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    /** Starts or stops the loop; a call that changes nothing does nothing. */
    fun set(on: Boolean) {
        if (on == running) return
        if (on) {
            job = scope.launch {
                while (isActive) {
                    tick()
                    delay(periodMs)
                }
            }
        } else {
            job?.cancel()
            job = null
        }
    }
}

/**
 * Whether the video player's position clock runs: while the video plays or buffers to play, and while it
 * [isLoading] (a paused video keeps loading: the buffered bar grows until the buffer is full, then no wake-ups).
 */
internal fun videoClockRuns(playWhenReady: Boolean, playbackState: Int, isLoading: Boolean): Boolean =
    keepsScreenOn(playWhenReady, playbackState) || isLoading

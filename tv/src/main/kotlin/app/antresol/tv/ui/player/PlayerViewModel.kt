package app.antresol.tv.ui.player

import android.content.Context
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.playback.PendingWrites
import app.antresol.tv.settings.TvPreferences
import app.antresol.ui.playback.ResumeMode
import app.antresol.ui.playback.VideoPlayerOptions
import app.antresol.ui.playback.VideoPlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The TV player: the shared [VideoPlayerViewModel] (sessions, progress, tracks, recovery) plus the
 * remote's seeking: Left/Right held down scrub a target on the seek bar and seek once on release,
 * number keys jump to tenths of the video.
 */
class PlayerViewModel(
    context: Context,
    client: AntresolClient,
    appScope: CoroutineScope,
    itemId: Long,
    resume: ResumeMode,
    preferences: TvPreferences = TvPreferences(),
    pendingWrites: PendingWrites = PendingWrites(),
) : VideoPlayerViewModel(
    context = context,
    client = client,
    appScope = appScope,
    itemId = itemId,
    resume = resume,
    options = VideoPlayerOptions(
        defaultQuality = preferences.defaultQuality,
        subtitleLanguage = preferences.subtitleLanguage,
        limitToDisplay = true,
    ),
    pendingWrites = pendingWrites,
) {
    private var scrubbing = false
    private var scrubJob: Job? = null

    /**
     * Left/Right held down: moves the target shown on the seek bar, faster the longer the key is
     * held (10 s, then 30 s, 60 s and 2 % of the film per repeat). [commitScrub] seeks once.
     */
    fun scrub(forward: Boolean, repeatCount: Int) {
        if (!canSeek) return
        val step = scrubStepMs(repeatCount)
        preview(targetBaseMs() + if (forward) step else -step)
        scrubbing = true
        // A lost key-up still ends the scrub.
        scrubJob?.cancel()
        scrubJob = viewModelScope.launch {
            delay(SCRUB_IDLE_MS)
            commitScrub()
        }
    }

    /** The scrub key was released: seek to the target shown. */
    fun commitScrub() {
        scrubJob?.cancel()
        scrubJob = null
        if (!scrubbing) return
        scrubbing = false
        seekTo(targetBaseMs())
    }

    /** Number keys: 0–9 jump to 0–90 % of the video. */
    fun jumpToTenth(tenth: Int) {
        val duration = knownDurationMs()
        if (!canSeek || duration <= 0) return
        seekTo(duration * tenth.coerceIn(0, 9) / 10)
    }

    private fun scrubStepMs(repeatCount: Int): Long = when {
        repeatCount < 8 -> SEEK_STEP_MS
        repeatCount < 20 -> 30_000L
        repeatCount < 40 -> 60_000L
        else -> maxOf(60_000L, knownDurationMs() / 50)
    }

    private companion object {
        const val SCRUB_IDLE_MS = 1_200L
    }
}

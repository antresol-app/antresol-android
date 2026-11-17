package app.antresol.ui.trailers

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.antresol.core.api.AntresolClient
import app.antresol.core.playback.TrailerPlayback
import app.antresol.ui.playback.PlaybackUnavailableException
import app.antresol.ui.playback.buildVideoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Test tags of the trailer screens. */
object TrailerTags {
    const val LOADING = "trailer-loading"
    const val PLAYING = "trailer-playing"
    const val FAILED = "trailer-failed"

    /** The embedded player's rectangle (the WebView). */
    const val EMBED = "trailer-embed"
}

/** What the trailer player shows. */
sealed interface TrailerPhase {
    /** The server is fetching the trailer, or the player is buffering its start. */
    data object Loading : TrailerPhase
    data object Playing : TrailerPhase
    data class Failed(val error: Throwable) : TrailerPhase

    /** Played to the end: the player closes. */
    data object Finished : TrailerPhase
}

/**
 * Plays one trailer that the server fetches and caches ([TrailerPlayback]) in the app's own
 * ExoPlayer, full screen. Deliberately simple: no progress reports, no Continue Watching, no next
 * episode, no track menus. Shared by the TV and phone trailer screens.
 */
class TrailerPlayerViewModel(
    context: Context,
    client: AntresolClient,
    /** The trailer's [app.antresol.core.model.Trailer.play] path. */
    private val play: String,
    /** The trailer's name, shown while it loads and with the controls. */
    val name: String,
) : ViewModel() {
    // Pinned: a profile switch mid-trailer never sends the next profile's token for this one.
    private val client: AntresolClient = client.pinned()

    val player: ExoPlayer = buildVideoPlayer(context, this.client)

    var phase: TrailerPhase by mutableStateOf(TrailerPhase.Loading)
        private set

    var isPlaying: Boolean by mutableStateOf(false)
        private set

    /** Playing or about to play; false while paused. Drives the Play/Pause button. */
    var playWhenReady: Boolean by mutableStateOf(false)
        private set

    var isBuffering: Boolean by mutableStateOf(false)
        private set

    var positionMs: Long by mutableLongStateOf(0)
        private set

    var bufferedMs: Long by mutableLongStateOf(0)
        private set

    var durationMs: Long by mutableLongStateOf(0)
        private set

    private var startJob: Job? = null
    private var backgrounded = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            this@TrailerPlayerViewModel.playWhenReady = playWhenReady
        }

        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING
            when (state) {
                Player.STATE_READY -> if (phase == TrailerPhase.Loading) phase = TrailerPhase.Playing
                Player.STATE_ENDED -> if (phase == TrailerPhase.Playing) phase = TrailerPhase.Finished
                else -> Unit
            }
            tick()
        }

        override fun onPlayerError(error: PlaybackException) {
            player.stop()
            phase = TrailerPhase.Failed(PlaybackUnavailableException(error))
        }
    }

    init {
        player.addListener(listener)
        start()
        viewModelScope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    /** «Повторить» after an error: ask the server again and start from the beginning. */
    fun retry() = start()

    private fun start() {
        startJob?.cancel()
        phase = TrailerPhase.Loading
        startJob = viewModelScope.launch {
            try {
                val url = requireNotNull(client.urls.resolve(TrailerPlayback.open(client, play).url)) { "Empty trailer url" }
                player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.VIDEO_MP4).build())
                player.prepare()
                player.playWhenReady = !backgrounded
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = TrailerPhase.Failed(e)
            }
        }
    }

    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.playWhenReady = !player.playWhenReady
    }

    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    fun seekTo(positionMs: Long) {
        if (phase != TrailerPhase.Playing) return
        val duration = player.duration.takeIf { it > 0 }
        player.seekTo(if (duration != null) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0))
        tick()
    }

    /** The screen went to the background: pause (a trailer has no session to stop). */
    fun onBackground() {
        backgrounded = true
        player.pause()
    }

    /** Back in front: stays paused where it was, like the film player. */
    fun onForeground() {
        backgrounded = false
    }

    private fun tick() {
        positionMs = player.currentPosition.coerceAtLeast(0)
        bufferedMs = player.bufferedPosition.coerceAtLeast(0)
        durationMs = player.duration.takeIf { it > 0 } ?: 0
    }

    override fun onCleared() {
        startJob?.cancel()
        player.removeListener(listener)
        player.release()
    }

    private companion object {
        const val TICK_MS = 500L
    }
}

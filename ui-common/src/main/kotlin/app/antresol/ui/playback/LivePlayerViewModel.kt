package app.antresol.ui.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.antresol.core.api.AntresolClient
import app.antresol.core.channels.LiveApi
import app.antresol.core.channels.LiveStream
import app.antresol.core.channels.LiveTvSession
import app.antresol.core.channels.LiveTvState
import app.antresol.core.channels.liveApi
import kotlinx.coroutines.launch

/**
 * The live channel player shared by the TV and phone apps: ExoPlayer on the channel's live HLS
 * (started at the live edge; no seeking, no progress), driven by [LiveTvSession], which opens the
 * stream with retries while the channel starts, switches channels ([zap]) and refreshes the guide.
 * Each app draws its own overlay from [state].
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LivePlayerViewModel(
    private val context: Context,
    client: AntresolClient,
    channelId: Long,
    api: LiveApi = client.liveApi(),
) : ViewModel() {
    private val client: AntresolClient = client.pinned()

    val player: ExoPlayer = buildPlayer()

    private val session = LiveTvSession(api, viewModelScope, channelId)

    var state: LiveTvState by mutableStateOf(session.state.value)
        private set

    /** Pictures are on screen for the current stream (false while it opens or rebuffers from scratch). */
    var isPlaying: Boolean by mutableStateOf(false)
        private set

    var isBuffering: Boolean by mutableStateOf(false)
        private set

    private var loaded: LiveStream.Ready? = null
    private var backgrounded = false
    private var released = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (playing) session.streamPlaying()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            isBuffering = playbackState == Player.STATE_BUFFERING
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // Fell out of the sliding window (a long stall): jump back to the live edge.
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            val http = error.cause as? HttpDataSource.InvalidResponseCodeException
            if (http?.responseCode == 401) {
                // The media key died with its session; reopening fetches a new one.
                viewModelScope.launch { this@LivePlayerViewModel.client.mediaKeys.invalidate() }
            }
            isPlaying = false
            loaded = null
            session.streamFailed()
        }
    }

    init {
        player.addListener(listener)
        viewModelScope.launch {
            session.state.collect { next ->
                state = next
                apply(next.stream)
            }
        }
        session.start()
    }

    /** Channel up (+1, the next number) or down (−1), wrapping. False when there is no other channel. */
    fun zap(step: Int): Boolean = session.zap(step)

    /** "Try Again" after the channel could not be shown. */
    fun retry() = session.retry()

    /** The app went to background: a live stream is not paused but dropped, and reopened at the live edge. */
    fun onBackground() {
        if (backgrounded || released) return
        backgrounded = true
        session.stop()
        player.stop()
        player.clearMediaItems()
        loaded = null
        isPlaying = false
    }

    fun onForeground() {
        if (!backgrounded || released) return
        backgrounded = false
        session.start()
    }

    private fun apply(stream: LiveStream) {
        if (released || backgrounded) return
        when (stream) {
            is LiveStream.Ready -> if (stream != loaded) {
                loaded = stream
                player.setMediaItem(
                    MediaItem.Builder()
                        .setUri(stream.url)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build(),
                )
                player.prepare()
                player.play()
            }
            // A new channel or a retry: the old picture goes at once.
            is LiveStream.Starting, is LiveStream.Failed -> if (loaded != null || player.mediaItemCount > 0) {
                loaded = null
                isPlaying = false
                player.stop()
                player.clearMediaItems()
            }
        }
    }

    override fun onCleared() {
        released = true
        session.stop()
        player.removeListener(listener)
        player.release()
    }

    private fun buildPlayer(): ExoPlayer {
        // The playlist URL and every segment line carry ?mk=; the shared client adds no Bearer then.
        val http = OkHttpDataSource.Factory(client.httpClient)
        val dataSources = DefaultDataSource.Factory(context, http)
        return ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}

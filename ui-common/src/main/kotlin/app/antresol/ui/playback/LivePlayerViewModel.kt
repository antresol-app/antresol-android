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
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.antresol.core.api.AntresolClient
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.channels.Channels
import app.antresol.core.channels.LiveApi
import app.antresol.core.channels.LiveTrackChoice
import app.antresol.core.channels.LiveTrackSelection
import app.antresol.core.channels.LiveTracks
import app.antresol.core.channels.SubtitleChoice
import app.antresol.core.channels.MiniGuide
import app.antresol.core.channels.liveMiniGuide
import app.antresol.core.channels.LiveStream
import app.antresol.core.channels.LiveTvSession
import app.antresol.core.channels.LiveTvState
import app.antresol.core.channels.liveApi
import app.antresol.core.model.ChannelProgram
import app.antresol.core.model.ProgramTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The live channel player shared by the TV and phone apps: ExoPlayer on the channel's live HLS
 * (started at the live edge; no seeking, no progress), driven by [LiveTvSession], which opens the
 * stream with retries while the channel starts, switches channels inside the list it was opened
 * from ([filter], [zap]) and refreshes the guide. Audio and subtitles are the master playlist's
 * renditions, picked by NAME from the programme's `audio`/`subtitles` ([LiveTracks]).
 *
 * TV is TV: this player sends no progress, playback reports, watched flags, track memory or
 * scrobbles. Its only server calls are those of [LiveApi] (channel list, guide, stream, the last
 * channel, favourites, the profile's languages read-only) and the media key; it never touches
 * [app.antresol.core.playback.PlaybackReporter], `/api/play` or `/api/progress`.
 * Each app draws its own overlay from [state].
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LivePlayerViewModel(
    private val context: Context,
    client: AntresolClient,
    channelId: Long,
    filter: ChannelFilter = ChannelFilter.All,
    api: LiveApi = client.liveApi(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val client: AntresolClient = client.pinned()

    val player: ExoPlayer = buildPlayer()

    private val session = LiveTvSession(api, viewModelScope, channelId, filter)

    /** The viewer's audio/subtitle choice for this player only (never sent to the server). */
    var trackChoice: LiveTrackChoice by mutableStateOf(LiveTrackChoice())
        private set

    /** The renditions selected for the programme on air (the picker's ticks). */
    var trackSelection: LiveTrackSelection by mutableStateOf(LiveTrackSelection(null, null))
        private set

    var state: LiveTvState by mutableStateOf(session.state.value)
        private set

    /** Pictures are on screen for the current stream (false while it opens or rebuffers from scratch). */
    var isPlaying: Boolean by mutableStateOf(false)
        private set

    var isBuffering: Boolean by mutableStateOf(false)
        private set

    /**
     * A channel switch is waiting for the new channel's first frame. The previous channel's last
     * frame stays on screen meanwhile (the surface keeps it, see `keepContentOnPlayerReset`); the
     * apps dim it while [hasPicture] says there is one.
     */
    var awaitingPicture: Boolean by mutableStateOf(true)
        private set

    /** Some channel has drawn a frame on the surface since the player opened. */
    var hasPicture: Boolean by mutableStateOf(false)
        private set

    /** The programme on air now (from the guide, else the channel list). */
    val onAirProgram: ChannelProgram?
        get() = state.onAir(clock()).first

    /** The picker's audio choices: the programme's renditions with their labels (empty when unknown). */
    val audioOptions: List<ProgramTrack>
        get() = onAirProgram?.audio.orEmpty()

    val subtitleOptions: List<ProgramTrack>
        get() = onAirProgram?.subtitles.orEmpty()

    /** The mini-guide: the list the player was opened from, by number, with the current channel. */
    val miniGuide: MiniGuide
        get() = liveMiniGuide(state, clock())

    private var loaded: LiveStream.Ready? = null
    private var backgrounded = false
    private var released = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (playing) session.streamPlaying()
        }

        override fun onRenderedFirstFrame() {
            hasPicture = true
            awaitingPicture = false
        }

        override fun onTracksChanged(tracks: Tracks) {
            applyTracks()
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
            player.stop()
            session.streamFailed()
        }
    }

    init {
        player.addListener(listener)
        viewModelScope.launch {
            session.state.collect { next ->
                val switched = next.channelId != state.channelId
                state = next
                if (switched) awaitingPicture = true
                apply(next.stream)
                updateMetadata()
                applyTracks()
            }
        }
        // The programme changes between refreshes (short ones every minute or so).
        viewModelScope.launch {
            while (true) {
                delay(METADATA_TICK_MS)
                updateMetadata()
                applyTracks()
            }
        }
        session.start()
    }

    /** Switches to [channelId] (from the mini-guide). False when it is already on. */
    fun tune(channelId: Long): Boolean = session.tune(channelId)

    /** Channel up (+1, the next number) or down (−1), wrapping. False when there is no other channel. */
    fun zap(step: Int): Boolean = session.zap(step)

    /** Favourite on/off for [channelId] (the player's star). */
    fun toggleFavorite(channelId: Long = state.channelId) {
        val channel = state.channels.firstOrNull { it.id == channelId } ?: return
        session.setFavorite(channelId, !channel.favorite)
    }

    /**
     * The viewer picks an audio rendition: it sticks for the rest of this player by its `name`
     * (across programmes and channel switches), falling back by language ([LiveTracks.select]).
     */
    fun selectAudio(track: ProgramTrack) {
        trackChoice = trackChoice.copy(audio = track)
        applyTracks()
    }

    /** The viewer picks subtitles, or none (null). */
    fun selectSubtitles(track: ProgramTrack?) {
        trackChoice = trackChoice.copy(subtitles = track?.let { SubtitleChoice.On(it) } ?: SubtitleChoice.Off)
        applyTracks()
    }

    /** "Try Again" after the channel could not be shown. */
    fun retry() = session.retry()

    /** The app went to background: a live stream is not paused but dropped, and reopened at the live edge. */
    fun onBackground() {
        if (backgrounded || released) return
        backgrounded = true
        session.stop()
        player.stop()
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
                awaitingPicture = true
                player.setMediaItem(mediaItem(stream.url, metadata()))
                player.prepare()
                player.play()
            }
            // A new channel or a retry: the old stream stops (it would keep its encoder busy on the
            // server), but its last frame stays on the surface until the new one draws.
            is LiveStream.Starting, is LiveStream.Failed -> if (loaded != null) {
                loaded = null
                isPlaying = false
                player.stop()
            }
        }
    }

    /**
     * Selects the renditions for the programme on air: the HLS `NAME` of an `EXT-X-MEDIA` is the
     * [androidx.media3.common.Format.label] of its track, so the override picks the group whose
     * label equals the entry's `name`. Subtitles off disables the text renderer.
     */
    private fun applyTracks() {
        if (released) return
        val selection = LiveTracks.select(onAirProgram, state.prefs, trackChoice)
        trackSelection = selection
        val groups = player.currentTracks.groups
        val builder = player.trackSelectionParameters.buildUpon()
        selection.audio?.let { wanted -> groups.rendition(C.TRACK_TYPE_AUDIO, wanted) }?.let { group ->
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        val subtitles = selection.subtitles
        if (subtitles == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            groups.rendition(C.TRACK_TYPE_TEXT, subtitles)?.let { group ->
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            }
        }
        val next = builder.build()
        if (next != player.trackSelectionParameters) player.trackSelectionParameters = next
    }

    /**
     * The master's rendition for [track]: the group whose label (the `NAME`) equals its `name`; a
     * name the master does not have (a key that joined after this run started) falls back to the
     * first rendition in the track's language. Nothing depends on the master's order or count.
     */
    private fun List<Tracks.Group>.rendition(type: Int, track: ProgramTrack): Tracks.Group? {
        val ofType = filter { it.type == type }
        ofType.firstOrNull { group -> group.formats().any { it.label == track.name || it.labels.any { l -> l.value == track.name } } }
            ?.let { return it }
        val lang = LiveTracks.langOf(track).ifEmpty { return null }
        return ofType.firstOrNull { group -> group.formats().any { format -> format.language?.let(LiveTracks::lang) == lang } }
    }

    private fun Tracks.Group.formats() = (0 until length).map(::getTrackFormat)

    /** What the system's media controls show: the programme as the title, the channel as the artist. */
    private fun metadata(): MediaMetadata {
        val channel = state.channel
        val (onAir, _) = state.onAir(clock())
        return MediaMetadata.Builder()
            .setTitle(onAir?.let(Channels::programLine) ?: channel?.name)
            .setArtist(channel?.name)
            .setDisplayTitle(onAir?.title ?: channel?.name)
            .setSubtitle(channel?.name)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_TV_CHANNEL)
            .build()
    }

    private fun updateMetadata() {
        if (released || backgrounded) return
        val current = player.currentMediaItem ?: return
        val url = loaded?.url ?: return
        if (current.localConfiguration?.uri?.toString() != url) return
        val next = metadata()
        if (next == current.mediaMetadata) return
        // Same URI: ExoPlayer updates the item in place without reloading the stream.
        player.replaceMediaItem(player.currentMediaItemIndex, mediaItem(url, next))
    }

    private fun mediaItem(url: String, metadata: MediaMetadata): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .setMediaMetadata(metadata)
        .build()

    companion object {
        /** How often the media session's title is checked against the programme on air. */
        const val METADATA_TICK_MS = 10_000L
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

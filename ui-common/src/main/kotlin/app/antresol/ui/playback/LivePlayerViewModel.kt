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
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.antresol.core.api.AntresolClient
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.channels.ExternalTrackMemory
import app.antresol.core.channels.ExternalTracks
import app.antresol.core.channels.StreamTrack
import app.antresol.core.channels.forMemory
import app.antresol.core.channels.Channels
import app.antresol.core.channels.LiveApi
import app.antresol.core.channels.LiveClock
import app.antresol.core.channels.LiveEdge
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
 * (started at the live edge; no progress), driven by [LiveTvSession], which opens the
 * stream with retries while the channel starts, switches channels inside the list it was opened
 * from ([filter], [zap]) and refreshes the guide. Audio and subtitles are the master playlist's
 * renditions, picked by NAME from the programme's `audio`/`subtitles` ([LiveTracks]).
 *
 * An external channel ([LiveStream.Ready.external]) is played straight from its provider with the
 * headers its playlist asked for (User-Agent, Referer) through a plain HTTP data source — never the
 * app's authorised client, so no Antresol credential reaches the provider. Its audio and subtitle
 * options are the stream's own tracks ([ExternalTracks]).
 *
 * The voice, on every channel: the viewer's choice on that channel (remembered per channel on the
 * device, [ExternalTrackMemory]), else the device's language (the system locale list), else the
 * profile's audio languages, else the original ([LiveTracks.select]).
 *
 * Live may be paused ([togglePause]; the media session's play/pause, picture-in-picture's and a
 * remote's key go through the same player). A resume picks up where it stopped while the stream
 * still holds that picture (a minute on our channels, the seekable window on an external one), else
 * at the live edge; more than 10 s behind the edge [behindLive] is set and [goLive] jumps back
 * ([LiveEdge]).
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
    platform: String = LiveTvSession.PLATFORM_ANDROID,
    private val trackMemory: ExternalTrackMemory = DeviceExternalTrackMemory(context),
) : ViewModel() {
    private val client: AntresolClient = client.pinned()

    val player: ExoPlayer = buildPlayer()

    private val session = LiveTvSession(api, viewModelScope, channelId, filter, platform)

    /** The viewer's audio/subtitle choice on the channel on screen (remembered per channel on the device, never sent). */
    var trackChoice: LiveTrackChoice by mutableStateOf(trackMemory.get(channelId) ?: LiveTrackChoice())
        private set

    /** The device's languages, most wanted first (the system locale list). */
    private val systemLanguages: List<String> = deviceLanguages()

    /** Paused by the viewer (the centre button, a key, the system's controls). */
    var paused: Boolean by mutableStateOf(false)
        private set

    /** More than 10 s behind the live edge: «В эфир» instead of «Прямой эфир». */
    var behindLive: Boolean by mutableStateOf(false)
        private set

    private var pausedAt = 0L
    private var behindAtPause = 0L

    /** The external stream's own audio / subtitle tracks (empty for Antresol's channels). */
    private var streamAudio: List<ProgramTrack> by mutableStateOf(emptyList())
    private var streamSubtitles: List<ProgramTrack> by mutableStateOf(emptyList())

    /** The channel on screen is external (its tracks come from the stream). */
    val isExternal: Boolean
        get() = (state.stream as? LiveStream.Ready)?.external ?: (state.channel?.external == true)

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
        get() = if (isExternal) streamAudio else onAirProgram?.audio.orEmpty()

    val subtitleOptions: List<ProgramTrack>
        get() = if (isExternal) streamSubtitles else onAirProgram?.subtitles.orEmpty()

    /** The mini-guide: the list the player was opened from, by number, with the current channel. */
    val miniGuide: MiniGuide
        get() = liveMiniGuide(state, clock())

    private var loaded: LiveStream.Ready? = null

    /** The MIME type the loaded external stream was given (HLS guessed for a URL without extension). */
    private var loadedMime: String? = null
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
            readStreamTracks(tracks)
            applyTracks()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            isBuffering = playbackState == Player.STATE_BUFFERING
        }

        // A pause from anywhere (the centre button, a key, the media session, picture-in-picture);
        // the resume decides between the stopped picture and the live edge.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                if (!paused) {
                    paused = true
                    pausedAt = clock()
                    behindAtPause = LiveEdge.behind(liveClock())
                }
                return
            }
            if (!paused) return
            paused = false
            val pausedFor = clock() - pausedAt
            if (LiveEdge.resumeAtEdge(own = loaded?.external != true, pausedMs = pausedFor, behindAtPauseMs = behindAtPause, clock = liveClock())) {
                player.seekToDefaultPosition()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // Fell out of the sliding window (a long stall): jump back to the live edge.
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            val current = loaded
            if (current?.external == true && loadedMime == MimeTypes.APPLICATION_M3U8 && guessedHls(current.url) &&
                error.errorCode in NOT_A_PLAYLIST
            ) {
                // A URL without an extension that is not an HLS playlist: try it as a plain stream (MPEG-TS).
                loadExternal(current, null)
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
                if (switched) {
                    awaitingPicture = true
                    streamAudio = emptyList()
                    streamSubtitles = emptyList()
                    trackChoice = trackMemory.get(next.channelId) ?: LiveTrackChoice()
                    behindLive = false
                }
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
        // «В эфир»: how far the picture is behind the live edge.
        viewModelScope.launch {
            while (true) {
                delay(EDGE_TICK_MS)
                if (!released) behindLive = LiveEdge.showGoLive(liveClock())
            }
        }
        session.start()
    }

    /** Where the player stands against the live edge (ExoPlayer's window: its start, default position and the position). */
    fun liveClock(): LiveClock {
        if (released || !player.isCurrentMediaItemLive) return LiveClock.NotLive
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return LiveClock.NotLive
        val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        return LiveClock(live = true, positionMs = player.currentPosition, edgeMs = window.defaultPositionMs, startMs = 0)
    }

    /** Pause, or resume (from the stopped picture while the stream holds it, else at the live edge). */
    fun togglePause() {
        if (released) return
        if (player.playWhenReady) player.pause() else player.play()
    }

    /** «В эфир»: back to the live edge (and playing). */
    fun goLive() {
        if (released) return
        player.seekToDefaultPosition()
        if (!player.playWhenReady) {
            paused = false
            player.play()
        }
        behindLive = false
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
     * The viewer picks an audio rendition: it sticks on this channel by its `name` (across its
     * programmes, remembered on the device), falling back by language ([LiveTracks.select]).
     */
    fun selectAudio(track: ProgramTrack) {
        remember(trackChoice.copy(audio = track))
        applyTracks()
    }

    /** The viewer picks subtitles, or none (null). */
    fun selectSubtitles(track: ProgramTrack?) {
        val subtitles = track?.let { SubtitleChoice.On(it) } ?: SubtitleChoice.Off
        remember(trackChoice.copy(subtitles = subtitles))
        applyTracks()
    }

    private fun remember(choice: LiveTrackChoice) {
        trackChoice = choice.forMemory()
        trackMemory.put(state.channelId, trackChoice)
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
                if (stream.external) {
                    loadExternal(stream, mimeFor(stream.url))
                } else {
                    loaded = stream
                    loadedMime = MimeTypes.APPLICATION_M3U8
                    awaitingPicture = true
                    player.setMediaItem(mediaItem(stream.url, metadata()))
                    player.prepare()
                    player.play()
                }
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
     * Loads an external stream with its own HTTP headers: a plain [DefaultHttpDataSource] (not the
     * app's client, which would add Antresol's credentials), User-Agent and Referer as the playlist
     * asked, redirects across http/https followed (the hidden-address form is a 302).
     */
    private fun loadExternal(stream: LiveStream.Ready, mime: String?) {
        loaded = stream
        loadedMime = mime
        awaitingPicture = true
        streamAudio = emptyList()
        streamSubtitles = emptyList()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(stream.userAgent?.takeIf { it.isNotBlank() } ?: Util.getUserAgent(context, USER_AGENT_APP))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(EXTERNAL_TIMEOUT_MS)
            .setReadTimeoutMs(EXTERNAL_TIMEOUT_MS)
        stream.referrer?.takeIf { it.isNotBlank() }?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .createMediaSource(mediaItem(stream.url, metadata(), mime))
        player.setMediaSource(source)
        player.prepare()
        player.play()
    }

    /** The external stream's audio and subtitle groups, as the picker's options. */
    private fun readStreamTracks(tracks: Tracks) {
        if (loaded?.external != true) {
            streamAudio = emptyList()
            streamSubtitles = emptyList()
            return
        }
        streamAudio = ExternalTracks.options(tracks.streamTracks(C.TRACK_TYPE_AUDIO), ::trackName)
        streamSubtitles = ExternalTracks.options(tracks.streamTracks(C.TRACK_TYPE_TEXT), ::trackName)
    }

    private fun Tracks.streamTracks(type: Int): List<StreamTrack> =
        groups.filter { it.type == type }.mapIndexedNotNull { index, group ->
            val supported = (0 until group.length).firstOrNull(group::isTrackSupported) ?: return@mapIndexedNotNull null
            val format = group.getTrackFormat(supported)
            StreamTrack(index, format.label, format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }, format.channelCount.coerceAtLeast(0), format.sampleMimeType)
        }

    /** A track the stream gave no name: its language's name, else «Дорожка N». */
    private fun trackName(index: Int, language: String?): String {
        val locale = language?.let(java.util.Locale::forLanguageTag)
        val name = locale?.getDisplayLanguage(context.resources.configuration.locales[0])?.takeIf { it.isNotBlank() && it != language }
        return name?.replaceFirstChar { it.titlecase() } ?: context.getString(app.antresol.ui.R.string.tv_track_n, index + 1)
    }

    /** The external track options' selection: the remembered choice, else the device's language, else the profile's. */
    private fun applyExternalTracks() {
        val program = ChannelProgram(audio = streamAudio, subtitles = streamSubtitles)
        val selection = LiveTracks.select(program, state.prefs, trackChoice, systemLanguages)
        trackSelection = selection
        val groups = player.currentTracks.groups
        val builder = player.trackSelectionParameters.buildUpon()
        selection.audio?.let { wanted -> groups.byIndex(C.TRACK_TYPE_AUDIO, wanted) }?.let { (group, track) ->
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
        }
        val subtitles = selection.subtitles
        if (subtitles == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            groups.byIndex(C.TRACK_TYPE_TEXT, subtitles)?.let { (group, track) ->
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
            }
        }
        val next = builder.build()
        if (next != player.trackSelectionParameters) player.trackSelectionParameters = next
    }

    /** The group of an [ExternalTracks] option and its first supported track. */
    private fun List<Tracks.Group>.byIndex(type: Int, track: ProgramTrack): Pair<Tracks.Group, Int>? {
        val index = ExternalTracks.group(track) ?: return null
        val group = filter { it.type == type }.getOrNull(index) ?: return null
        val supported = (0 until group.length).firstOrNull(group::isTrackSupported) ?: return null
        return group to supported
    }

    /**
     * Selects the renditions for the programme on air: the HLS `NAME` of an `EXT-X-MEDIA` is the
     * [androidx.media3.common.Format.label] of its track, so the override picks the group whose
     * label equals the entry's `name`. Subtitles off disables the text renderer.
     */
    private fun applyTracks() {
        if (released) return
        if (loaded?.external == true || (loaded == null && isExternal)) {
            applyExternalTracks()
            return
        }
        val selection = LiveTracks.select(onAirProgram, state.prefs, trackChoice, systemLanguages)
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
        player.replaceMediaItem(player.currentMediaItemIndex, mediaItem(url, next, loadedMime))
    }

    private fun mediaItem(url: String, metadata: MediaMetadata, mime: String? = MimeTypes.APPLICATION_M3U8): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMimeType(mime)
            .setMediaMetadata(metadata)
            .build()

    /**
     * An external URL's type: by its extension (`.m3u8`, `.mpd`, `.ts`, `.mp4`…), and HLS for a URL
     * without one (most IPTV streams; the hidden-address form) — retried as a plain stream if it
     * turns out not to be a playlist.
     */
    private fun mimeFor(url: String): String? = if (guessedHls(url)) MimeTypes.APPLICATION_M3U8 else null

    private fun guessedHls(url: String): Boolean =
        Util.inferContentType(android.net.Uri.parse(url)) == C.CONTENT_TYPE_OTHER && !hasMediaExtension(url) ||
            Util.inferContentType(android.net.Uri.parse(url)) == C.CONTENT_TYPE_HLS

    private fun hasMediaExtension(url: String): Boolean {
        val last = android.net.Uri.parse(url).lastPathSegment.orEmpty().lowercase()
        return last.contains('.') && last.substringAfterLast('.') in MEDIA_EXTENSIONS
    }

    companion object {
        private const val USER_AGENT_APP = "Antresol"
        private const val EXTERNAL_TIMEOUT_MS = 15_000
        private val MEDIA_EXTENSIONS = setOf("ts", "mp4", "m4v", "mkv", "mp3", "aac", "flv", "mov", "webm", "mpd", "ism", "isml")
        private val NOT_A_PLAYLIST = setOf(
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )

        /** How often the media session's title is checked against the programme on air. */
        const val METADATA_TICK_MS = 10_000L

        /** How often the distance to the live edge is read. */
        const val EDGE_TICK_MS = 500L

        /** The device's languages (the system locale list), as BCP 47 tags. */
        fun deviceLanguages(): List<String> {
            val list = android.os.LocaleList.getDefault()
            return (0 until list.size()).map { list[it].toLanguageTag() }
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
            .setLoadControl(videoLoadControl(context))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}

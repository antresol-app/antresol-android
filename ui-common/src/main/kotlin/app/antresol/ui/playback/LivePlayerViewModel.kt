package app.antresol.ui.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import app.antresol.core.model.Quality
import app.antresol.core.model.QualityInfo
import app.antresol.core.api.attempt
import app.antresol.core.channels.LiveQuality
import app.antresol.core.channels.LiveQualityOption
import app.antresol.core.channels.ExternalSourceMemory
import app.antresol.core.channels.LivePlaybackPath
import app.antresol.core.channels.MediaCodecs
import kotlinx.coroutines.Job
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
 * device, [ExternalTrackMemory]), else the profile's audio languages («Мои языки», as for films),
 * else the device's language (the system locale list), else the original ([LiveTracks.select]).
 * Subtitles: the viewer's choice, else the profile's subtitle mode and languages. The options are
 * the programme's renditions from the guide; when the guide names none but the stream has text
 * tracks (`EXT-X-MEDIA TYPE=SUBTITLES`), the stream's own tracks are offered instead.
 *
 * The banner's [playbackPath] tells how the stream arrives («Напрямую · host» / «Через Antresol»),
 * its format and codecs, and the source tried. An external stream that shows pictures without a sound
 * the device decodes, or that waits for data longer than [STALL_MS] while playing, gives way to its
 * next source ([LiveTvSession.streamSilent], [LiveTvSession.streamStalled]); such sources are kept per
 * channel on the device ([DeviceExternalSourceMemory]) and tried last.
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
    private val api: LiveApi = client.liveApi(),
    private val clock: () -> Long = System::currentTimeMillis,
    platform: String = LiveTvSession.PLATFORM_ANDROID,
    private val trackMemory: ExternalTrackMemory = DeviceExternalTrackMemory(context),
    /** The [Quality] our channels open in (the app's video quality setting, shared with films). */
    quality: String = Quality.AUTO,
    /** The viewer picked a quality for our channels: the app saves it as its setting. */
    private val onQualityChosen: (String) -> Unit = {},
    sourceMemory: ExternalSourceMemory = DeviceExternalSourceMemory(context),
) : ViewModel() {
    private val client: AntresolClient = client.pinned()

    val player: ExoPlayer = buildVideoPlayer(context, this.client)

    private val session = LiveTvSession(api, viewModelScope, channelId, filter, platform, quality, sourceMemory)

    /** The quality our channels are opened in («Качество»). */
    var quality: String by mutableStateOf(session.quality)
        private set

    /** What the server applies for [quality] (for «Авто · 720p»); null until read. */
    private var qualityInfo: QualityInfo? by mutableStateOf(null)
    private var qualityInfoFor: String? = null

    /** An external stream's video variants (heights) and the height the viewer limited it to (null: auto). */
    private var streamHeights: List<Int> by mutableStateOf(emptyList())
    private var externalMaxHeight: Int? by mutableStateOf(null)

    /**
     * The «Качество» choices: our channel — Авто / Оригинал / 1080p / 720p / 480p; an external one —
     * Авто and the stream's own heights when its master has several (else none: no menu).
     */
    val qualityOptions: List<LiveQualityOption>
        get() = if (isExternal) LiveQuality.external(streamHeights, externalMaxHeight) else LiveQuality.own(quality, qualityInfo)

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

    /** The stream's own audio / subtitle tracks (the options of an external channel; subtitles also for ours when the guide lists none). */
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

    /** How the stream on screen arrives, its format and codecs (the banner's quiet line); null while none is loaded. */
    var playbackPath: LivePlaybackPath? by mutableStateOf(null)
        private set

    /** Waits a moment after the first frame, then looks at the audio tracks. */
    private var audioCheck: Job? = null

    /** Counts a stall while playing; past [STALL_MS] the stream is stuck. */
    private var stallWatch: Job? = null

    /** The stream on screen has drawn a frame (a stall before that is the start's timeout, not this watch). */
    private var streamStarted = false

    /** The programme on air now (from the guide, else the channel list). */
    val onAirProgram: ChannelProgram?
        get() = state.onAir(clock()).first

    /** The picker's audio choices: the programme's renditions with their labels (empty when unknown). */
    val audioOptions: List<ProgramTrack>
        get() = if (isExternal) streamAudio else onAirProgram?.audio.orEmpty()

    val subtitleOptions: List<ProgramTrack>
        get() = if (isExternal) streamSubtitles else ownSubtitles(onAirProgram)

    /** Our channel's subtitles: the guide's renditions, else the text tracks the stream carries. */
    private fun ownSubtitles(program: ChannelProgram?): List<ProgramTrack> =
        program?.subtitles.orEmpty().ifEmpty { streamSubtitles }

    /** [subtitleOptions] come from the stream's tracks (picked by group, not by the guide's name). */
    private val subtitlesFromStream: Boolean
        get() = isExternal || onAirProgram?.subtitles.isNullOrEmpty()

    /** The mini-guide: the list the player was opened from, by number, with the current channel. */
    val miniGuide: MiniGuide
        get() = liveMiniGuide(state, clock())

    /**
     * «В эфир»: how far the picture is behind the live edge. It runs also while paused (the edge moves
     * on and the button must appear), but not in the background, where the stream is dropped.
     */
    private val edgeTicker = Ticker(viewModelScope, EDGE_TICK_MS) {
        if (!released) behindLive = LiveEdge.showGoLive(liveClock())
    }

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
            if (!streamStarted) {
                streamStarted = true
                checkAudioSoon()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            readStreamTracks(tracks)
            applyTracks()
            updatePath()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            isBuffering = playbackState == Player.STATE_BUFFERING
            watchStall(playbackState)
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
            if (current?.external == true && loadedMime == MimeTypes.APPLICATION_M3U8 && current.format.isNullOrBlank() && guessedHls(current.url) &&
                error.errorCode in NOT_A_PLAYLIST
            ) {
                // A URL without an extension that is not an HLS playlist: try it as a plain stream (MPEG-TS).
                loadExternal(current, null)
                return
            }
            if (error.isUnauthorized) {
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
                    streamHeights = emptyList()
                    clearExternalLimit()
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
        edgeTicker.set(true)
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

    /** «Предыдущий канал»; false when there is none yet. */
    fun tunePrevious(): Boolean = session.tunePrevious()

    /** Channel up (+1, the next number) or down (−1), wrapping. False when there is no other channel. */
    fun zap(step: Int): Boolean = session.zap(step)

    /** Zapping and the mini-guide move to the list of [filter] (a category picked in the player). */
    fun setFilter(filter: ChannelFilter) = session.setFilter(filter)

    /** Tunes in the channel numbered [number] (the remote's digits); false when there is none. */
    fun tuneNumber(number: Int): Boolean {
        val channel = state.channels.firstOrNull { it.number == number } ?: return false
        tune(channel.id)
        return true
    }

    /** Favourite on/off for the channel on (the player's star). */
    fun toggleFavorite() {
        val channel = state.channels.firstOrNull { it.id == state.channelId } ?: return
        session.setFavorite(channel.id, !channel.favorite)
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

    /**
     * The viewer picks a quality. Our channel: the stream is opened again with `?q=` (at the live
     * edge) and the choice saved as the app's setting. An external channel: the player keeps to the
     * stream's variants up to that height (not saved); «Авто» lifts the limit.
     */
    fun selectQuality(value: String) {
        if (released) return
        if (isExternal) {
            val height = LiveQuality.externalMaxHeight(value)
            if (height == externalMaxHeight) return
            externalMaxHeight = height
            val builder = player.trackSelectionParameters.buildUpon()
            if (height == null) builder.clearVideoSizeConstraints() else builder.setMaxVideoSize(Int.MAX_VALUE, height)
            player.trackSelectionParameters = builder.build()
            return
        }
        if (value == quality || value !in Quality.ALL) return
        quality = value
        qualityInfo = null
        qualityInfoFor = null
        onQualityChosen(value)
        session.setQuality(value)
    }

    private fun clearExternalLimit() {
        if (externalMaxHeight == null) return
        externalMaxHeight = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearVideoSizeConstraints().build()
    }

    /** Reads what the server applies for [quality] once per choice (the «Авто» detail). */
    private fun loadQualityInfo() {
        val wanted = quality
        if (qualityInfoFor == wanted) return
        qualityInfoFor = wanted
        viewModelScope.launch {
            val info = attempt { api.quality(wanted) }
            if (quality == wanted) qualityInfo = info else return@launch
            if (info == null) qualityInfoFor = null
        }
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
        edgeTicker.set(false)
        behindLive = false
        session.stop()
        player.stop()
        loaded = null
        isPlaying = false
    }

    fun onForeground() {
        if (!backgrounded || released) return
        backgrounded = false
        edgeTicker.set(true)
        session.start()
    }

    private fun apply(stream: LiveStream) {
        if (released || backgrounded) return
        when (stream) {
            is LiveStream.Ready -> if (stream != loaded) {
                if (stream.external) {
                    loadExternal(stream, mimeFor(stream.url, stream.format))
                } else {
                    loaded = stream
                    loadedMime = MimeTypes.APPLICATION_M3U8
                    streamLoaded()
                    awaitingPicture = true
                    streamHeights = emptyList()
                    clearExternalLimit()
                    loadQualityInfo()
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
                audioCheck?.cancel()
                stallWatch?.cancel()
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
        streamLoaded()
        awaitingPicture = true
        streamAudio = emptyList()
        streamSubtitles = emptyList()
        streamHeights = emptyList()
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

    /** A new stream goes to the player: its path from scratch, the checks wait for its first frame. */
    private fun streamLoaded() {
        streamStarted = false
        audioCheck?.cancel()
        stallWatch?.cancel()
        updatePath()
    }

    /** The banner's path: the loaded stream's route, format and source, the player's codecs. */
    private fun updatePath() {
        val stream = loaded ?: run {
            playbackPath = null
            return
        }
        val video = MediaCodecs.name(player.videoFormat?.sampleMimeType)
        val audio = MediaCodecs.name(player.audioFormat?.sampleMimeType)
        playbackPath = if (!stream.external) {
            LivePlaybackPath.own(video, audio)
        } else {
            // the source's own URL (the provider's, never the resolved redirect's)
            val url = if (stream.viaServer) "/" else session.currentSourceUrl() ?: stream.url
            LivePlaybackPath.external(url, stream.format ?: if (loadedMime == MimeTypes.VIDEO_MP2T) "ts" else null, stream.source, stream.sourceCount, video, audio)
        }
    }

    /**
     * A moment after an external stream's first frame: pictures but no audio track the device
     * plays (none at all, none supported, none selected) is a silent stream — the next source.
     */
    private fun checkAudioSoon() {
        audioCheck?.cancel()
        val current = loaded ?: return
        if (!current.external) return
        audioCheck = viewModelScope.launch {
            delay(AUDIO_CHECK_MS)
            if (released || loaded !== current) return@launch
            val groups = player.currentTracks.groups
            if (groups.none { it.type == C.TRACK_TYPE_VIDEO }) return@launch
            val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val playable = audio.any { group -> (0 until group.length).any { group.isTrackSupported(it) } } &&
                player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
            if (playable) session.streamHasSound() else session.streamSilent()
        }
    }

    /** Buffering while it should play, after the stream started: past [STALL_MS] it is stuck. */
    private fun watchStall(playbackState: Int) {
        if (playbackState != Player.STATE_BUFFERING) {
            stallWatch?.cancel()
            stallWatch = null
            return
        }
        if (!streamStarted || stallWatch?.isActive == true) return
        val current = loaded ?: return
        stallWatch = viewModelScope.launch {
            delay(STALL_MS)
            if (released || loaded !== current || player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return@launch
            isPlaying = false
            loaded = null
            player.stop()
            session.streamStalled()
        }
    }

    /** The external stream's audio and subtitle groups, as the picker's options. */
    private fun readStreamTracks(tracks: Tracks) {
        val external = loaded?.external == true
        streamAudio = if (external) ExternalTracks.options(tracks.streamTracks(C.TRACK_TYPE_AUDIO), ::trackName) else emptyList()
        // Ours too: a stream whose subtitle renditions the guide does not list still offers them.
        streamSubtitles = ExternalTracks.options(tracks.streamTracks(C.TRACK_TYPE_TEXT), ::trackName)
        // An external master's variants (one adaptive video group); ours go through `?q=` instead.
        streamHeights = if (external) {
            tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                .flatMap { group -> (0 until group.length).filter(group::isTrackSupported).map { group.getTrackFormat(it).height } }
        } else {
            emptyList()
        }
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

    /**
     * Selects the audio and subtitle tracks; subtitles off disables the text renderer.
     *
     * A channel of the server: the renditions for the programme on air. The HLS `NAME` of an
     * `EXT-X-MEDIA` is the [androidx.media3.common.Format.label] of its track, so the override
     * picks the group whose label equals the entry's `name`.
     *
     * An external stream: its own track options; the remembered choice, else the device's
     * language, else the profile's.
     */
    private fun applyTracks() {
        if (released) return
        val external = loaded?.external == true || (loaded == null && isExternal)
        val onAir = onAirProgram
        val program = if (external) {
            ChannelProgram(audio = streamAudio, subtitles = streamSubtitles)
        } else {
            onAir?.copy(subtitles = ownSubtitles(onAir)) ?: ChannelProgram(subtitles = streamSubtitles).takeIf { streamSubtitles.isNotEmpty() }
        }
        val subsByIndex = external || subtitlesFromStream
        val selection = LiveTracks.select(program, state.prefs, trackChoice, systemLanguages)
        trackSelection = selection
        val groups = player.currentTracks.groups
        fun find(type: Int, track: ProgramTrack, byIndex: Boolean): Pair<Tracks.Group, Int>? =
            if (byIndex) groups.byIndex(type, track) else groups.rendition(type, track)?.let { it to 0 }
        val builder = player.trackSelectionParameters.buildUpon()
        // The wanted languages also go to the track selector: it picks by them whenever the override
        // finds no group (tracks not read yet, a rendition the guide names differently).
        builder.setPreferredAudioLanguages(*listOfNotNull(selection.audio?.let(LiveTracks::langOf)?.ifEmpty { null }).toTypedArray())
        selection.audio?.let { wanted -> find(C.TRACK_TYPE_AUDIO, wanted, external) }?.let { (group, track) ->
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
        }
        val subtitles = selection.subtitles
        if (subtitles == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setPreferredTextLanguages(*listOfNotNull(LiveTracks.langOf(subtitles).ifEmpty { null }).toTypedArray())
            // Our renditions are DEFAULT=NO: without a language or an override nothing would be selected.
            builder.setSelectUndeterminedTextLanguage(true)
            find(C.TRACK_TYPE_TEXT, subtitles, subsByIndex)?.let { (group, track) ->
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
    private fun mimeFor(url: String, format: String? = null): String? = when (format) {
        // The server knows what the stream is (it probed or was told): no guess, no retry as another type.
        "hls" -> MimeTypes.APPLICATION_M3U8
        "ts" -> MimeTypes.VIDEO_MP2T
        else -> if (guessedHls(url)) MimeTypes.APPLICATION_M3U8 else null
    }

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

        /** How long after an external stream's first frame its audio tracks are looked at. */
        const val AUDIO_CHECK_MS = 3_000L

        /** Waiting for data this long while playing is a stuck stream. */
        const val STALL_MS = 8_000L

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
}

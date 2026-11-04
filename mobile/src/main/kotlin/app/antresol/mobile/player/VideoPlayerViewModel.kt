package app.antresol.mobile.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.model.AudioTrack
import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.FileInfo
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.Quality
import app.antresol.core.model.SubTrack
import app.antresol.core.playback.Episodes
import app.antresol.core.playback.PlaybackReporter
import app.antresol.core.playback.Resume
import app.antresol.core.playback.VideoSession
import app.antresol.core.playback.VideoSource
import app.antresol.core.playback.WebVtt
import app.antresol.core.playback.playbackApi
import app.antresol.core.playback.videoApi
import app.antresol.ui.playback.DeviceCaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How the player picks its start position. */
enum class ResumeMode {
    /** Ask "Resume from 12:34?" when there is saved progress (episode cards, detail rows). */
    Ask,

    /** Continue from saved progress without asking (Resume buttons, Continue Watching). */
    Resume,

    /** Start at 0 ("Play from Beginning"). */
    FromStart,
}

/** What the player screen shows. */
sealed interface PlayerPhase {
    data object Loading : PlayerPhase
    data class AskResume(val positionMs: Long) : PlayerPhase
    data object Playing : PlayerPhase
    /** The next episode; [secondsLeft] counts down to autoplay, null when autoplay is off. */
    data class UpNext(val episode: EpisodeCard, val secondsLeft: Int?) : PlayerPhase
    data class Failed(val error: Throwable) : PlayerPhase

    /** Played to the end with nothing after it: the screen closes. */
    data object Finished : PlayerPhase
}

/** The item has no playable file, or the player gave up on the stream. */
class PlaybackUnavailableException(cause: Throwable? = null) : Exception(cause)

/**
 * Plays one movie or episode (and the following episodes) with ExoPlayer, following the spec's
 * video flow: `/api/play` with the device caps, direct or HLS via `?mk=`, HLS keepalive and stop,
 * progress every 10 s and on pause/exit, `replaceSession` for seeks before the HLS start, audio
 * track and quality changes, side-loaded WebVTT subtitles shifted to the HLS start, and recovery
 * when the server drops the session. ExoPlayer lives here so it survives recomposition.
 */
class VideoPlayerViewModel(
    private val context: Context,
    private val client: AntresolClient,
    private val appScope: CoroutineScope,
    itemId: Long,
    resume: ResumeMode,
    /** `/api/play` quality for the first open (the user's setting). */
    private val defaultQuality: String,
    /** Start the next episode after the countdown. */
    private val autoplayNext: () -> Boolean,
) : ViewModel() {
    val player: ExoPlayer = buildPlayer()

    var phase: PlayerPhase by mutableStateOf(PlayerPhase.Loading)
        private set

    /** The movie or episode being played (after a series id was resolved to its episode). */
    var item: ItemDetail? by mutableStateOf(null)
        private set

    /** The episode after the current one, for the "Next Episode" button and autoplay. */
    var nextEpisode: EpisodeCard? by mutableStateOf(null)
        private set

    var source: VideoSource? by mutableStateOf(null)
        private set

    var isPlaying: Boolean by mutableStateOf(false)
        private set

    var isBuffering: Boolean by mutableStateOf(false)
        private set

    /** Playing or about to play (buffering); false while paused. Drives the Play/Pause button. */
    var playWhenReady: Boolean by mutableStateOf(false)
        private set

    /** A stream is being reopened (seek before the HLS start, audio or quality change). */
    var isSwitching: Boolean by mutableStateOf(false)
        private set

    /** File position shown by the controls; during a pending seek, its target. */
    var positionMs: Long by mutableLongStateOf(0)
        private set

    var bufferedMs: Long by mutableLongStateOf(0)
        private set

    var durationMs: Long by mutableLongStateOf(0)
        private set

    var selectedSubtitle: SubTrack? by mutableStateOf(null)
        private set

    /** A string resource to show briefly (subtitle download failed); the screen clears it. */
    var notice: Int? by mutableStateOf(null)

    val audioTracks: List<AudioTrack> get() = source?.play?.audio.orEmpty()
    val selectedAudioIndex: Int? get() = source?.play?.audioIndex
    val subtitleTracks: List<SubTrack> get() = source?.play?.subtitles.orEmpty().filter { it.url != null }
    val quality: String get() = source?.play?.quality?.selected?.ifEmpty { null } ?: Quality.AUTO

    private var currentItemId = itemId
    private var pendingFile: FileInfo? = null
    private var session: VideoSession? = null
    private var reporter: PlaybackReporter? = null
    private val rawSubtitles = HashMap<String, String>()
    private val subtitleDir = File(context.cacheDir, "subtitles")
    private var pendingSeekMs: Long? = null
    private var lastFileMs = 0L
    private var suspendedAtMs: Long? = null
    private var recoveries = 0
    private var released = false
    private var startJob: Job? = null
    private var reopenJob: Job? = null
    private var seekJob: Job? = null
    private var subtitleJob: Job? = null
    private var upNextJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING
            when (state) {
                Player.STATE_READY -> recoveries = 0
                Player.STATE_ENDED -> onEnded()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            this@VideoPlayerViewModel.playWhenReady = playWhenReady
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                viewModelScope.launch { reporter?.flush() }
            }
        }

        override fun onPlayerError(error: PlaybackException) = recover(error)

        override fun onTracksChanged(tracks: Tracks) = applyTextSelection()
    }

    init {
        player.addListener(listener)
        start(itemId, resume)
        viewModelScope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    // region Starting

    private fun start(itemId: Long, mode: ResumeMode) {
        startJob?.cancel()
        phase = PlayerPhase.Loading
        startJob = viewModelScope.launch {
            try {
                var detail = client.item(itemId)
                if (detail.kind == Kind.SERIES || detail.kind == Kind.SEASON) {
                    val episode = Episodes.toPlay(detail) ?: throw PlaybackUnavailableException()
                    detail = client.item(episode.id)
                }
                val file = detail.files.firstOrNull() ?: throw PlaybackUnavailableException()
                item = detail
                currentItemId = detail.id
                findNextEpisode(detail)
                val saved = if (mode == ResumeMode.FromStart) 0L else savedPositionMs(detail.id)
                if (mode == ResumeMode.Ask && saved >= RESUME_PROMPT_MIN_MS) {
                    pendingFile = file
                    phase = PlayerPhase.AskResume(saved)
                } else {
                    begin(file, saved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = PlayerPhase.Failed(e)
            }
        }
    }

    /** The answer to the resume prompt. */
    fun chooseResume(resume: Boolean) {
        val prompt = phase as? PlayerPhase.AskResume ?: return
        val file = pendingFile ?: return
        pendingFile = null
        phase = PlayerPhase.Loading
        startJob = viewModelScope.launch {
            try {
                begin(file, if (resume) prompt.positionMs else 0L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = PlayerPhase.Failed(e)
            }
        }
    }

    private suspend fun savedPositionMs(itemId: Long): Long = try {
        (Resume.videoStartSec(client.progress(itemId)) * 1000).toLong()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        0L
    }

    private fun findNextEpisode(detail: ItemDetail) {
        nextEpisode = null
        val seriesId = detail.seriesId ?: return
        if (detail.kind != Kind.EPISODE) return
        viewModelScope.launch {
            nextEpisode = try {
                Episodes.after(client.item(seriesId), detail.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun begin(file: FileInfo, startMs: Long) {
        val newSession = VideoSession(client.videoApi(), file.id, DeviceCaps.current(context), quality = defaultQuality)
        session = newSession
        selectedSubtitle = null
        rawSubtitles.clear()
        val opened = newSession.open(startMs)
        val duration = opened.play.durationMs.takeIf { it > 0 } ?: file.durationMs ?: 0L
        durationMs = duration
        reporter = PlaybackReporter(
            api = client.playbackApi(),
            scope = viewModelScope,
            itemId = currentItemId,
            durationMs = duration,
            positionMs = ::reportedPositionMs,
            isPlaying = { !released && player.isPlaying },
            onSessionLost = { onSessionLost() },
        ).also { it.start(opened.session) }
        load(opened, opened.timeline.initialPlayerSeekMs, playWhenReady = true)
        phase = PlayerPhase.Playing
    }

    // endregion

    // region Player control

    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) return
        player.playWhenReady = !player.playWhenReady
    }

    /** Seeks by [deltaMs] on the file timeline (skip buttons, double taps). */
    fun seekBy(deltaMs: Long) = seekTo((pendingSeekMs ?: currentFileMs()) + deltaMs)

    /** Seeks to [fileMs] on the file timeline; before the HLS start this reopens the stream. */
    fun seekTo(fileMs: Long) {
        val current = source ?: return
        if (isSwitching) return
        val target = fileMs.coerceIn(0, (durationMs - END_GUARD_MS).coerceAtLeast(0))
        positionMs = target
        seekJob?.cancel()
        if (current.timeline.needsNewSession(target)) {
            pendingSeekMs = target
            // Wait for repeated skips to settle so one reopen covers several of them.
            seekJob = viewModelScope.launch {
                delay(SEEK_SETTLE_MS)
                reopen(target)
            }
        } else {
            pendingSeekMs = null
            player.seekTo(current.timeline.playerMs(target))
        }
    }

    fun selectAudio(track: AudioTrack) {
        if (track.index == selectedAudioIndex) return
        reopen(currentFileMs(), audioIndex = track.index)
    }

    fun selectQuality(value: String) {
        if (value == quality) return
        reopen(currentFileMs(), quality = value)
    }

    fun selectSubtitle(track: SubTrack?) {
        subtitleJob?.cancel()
        if (track == null) {
            selectedSubtitle = null
            applyTextSelection()
            return
        }
        val url = track.url ?: return
        subtitleJob = viewModelScope.launch {
            try {
                if (url !in rawSubtitles) rawSubtitles[url] = downloadVtt(url)
                selectedSubtitle = track
                val current = source ?: return@launch
                load(current, player.currentPosition, player.playWhenReady)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = app.antresol.ui.R.string.player_subtitles_failed
            }
        }
    }

    fun retry() {
        if (session != null && source != null) {
            phase = PlayerPhase.Playing
            reopen(lastFileMs)
        } else {
            start(currentItemId, ResumeMode.Resume)
        }
    }

    // endregion

    // region Episodes

    fun playNext() {
        val next = nextEpisode ?: return
        upNextJob?.cancel()
        upNextJob = null
        phase = PlayerPhase.Loading
        viewModelScope.launch {
            finishCurrent()
            start(next.id, ResumeMode.Resume)
        }
    }

    private fun onEnded() {
        if (phase != PlayerPhase.Playing) return
        viewModelScope.launch { reporter?.flush() }
        val next = nextEpisode
        if (next == null) {
            phase = PlayerPhase.Finished
            return
        }
        if (!autoplayNext()) {
            phase = PlayerPhase.UpNext(next, null)
            return
        }
        upNextJob = viewModelScope.launch {
            for (seconds in UP_NEXT_SECONDS downTo 1) {
                phase = PlayerPhase.UpNext(next, seconds)
                delay(1_000)
            }
            playNext()
        }
    }

    /** Final progress write and session stop for the item that is ending. */
    private suspend fun finishCurrent() {
        lastFileMs = currentFileMs()
        val ending = reporter
        reporter = null
        session = null
        source = null
        player.stop()
        player.clearMediaItems()
        ending?.stop()
    }

    // endregion

    // region Lifecycle

    /** The app went to background: pause, save, and give the HLS slot back to the profile. */
    fun onBackground() {
        if (released || source == null || phase != PlayerPhase.Playing) return
        player.pause()
        val current = session ?: return
        if (current.session == null) return
        suspendedAtMs = currentFileMs()
        appScope.launch {
            reporter?.flush()
            current.suspendSession()
            reporter?.attachSession(null)
        }
    }

    /** Back from background: reopen the suspended HLS stream, paused, where it was. */
    fun onForeground() {
        val at = suspendedAtMs ?: return
        suspendedAtMs = null
        reopen(at, playWhenReady = false)
    }

    override fun onCleared() {
        lastFileMs = currentFileMs()
        released = true
        player.removeListener(listener)
        player.release()
        val ending = reporter
        reporter = null
        appScope.launch {
            ending?.stop()
            withContext(Dispatchers.IO) { subtitleDir.deleteRecursively() }
        }
    }

    // endregion

    // region Streams

    private fun reopen(
        fileMs: Long,
        quality: String? = null,
        audioIndex: Int? = null,
        playWhenReady: Boolean = player.playWhenReady,
    ) {
        val current = session ?: return
        reopenJob?.cancel()
        isSwitching = true
        pendingSeekMs = fileMs
        positionMs = fileMs
        reopenJob = viewModelScope.launch {
            try {
                val opened = current.open(fileMs, quality ?: current.quality, audioIndex ?: current.audioIndex)
                reporter?.attachSession(opened.session)
                val playerMs = if (opened.isHls) (fileMs - opened.timeline.startMs).coerceAtLeast(0) else fileMs
                load(opened, playerMs, playWhenReady)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                player.pause()
                phase = PlayerPhase.Failed(e)
            } finally {
                pendingSeekMs = null
                isSwitching = false
            }
        }
    }

    /** Hands [opened] to ExoPlayer at [playerStartMs], with the selected subtitle side-loaded. */
    private suspend fun load(opened: VideoSource, playerStartMs: Long, playWhenReady: Boolean) {
        val subtitle = subtitleConfiguration(opened)
        source = opened
        val mediaItem = MediaItem.Builder()
            .setUri(opened.url)
            .apply { if (opened.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(listOfNotNull(subtitle))
            .build()
        player.setMediaItem(mediaItem, playerStartMs)
        player.playWhenReady = playWhenReady
        player.prepare()
        applyTextSelection()
    }

    /** Writes the selected subtitle, shifted to the stream's start, to a cache file. */
    private suspend fun subtitleConfiguration(opened: VideoSource): MediaItem.SubtitleConfiguration? {
        val track = selectedSubtitle ?: return null
        val raw = rawSubtitles[track.url] ?: return null
        val file = File(subtitleDir, "${session?.fileId}-${track.index}-${opened.timeline.startMs}.vtt")
        withContext(Dispatchers.IO) {
            subtitleDir.mkdirs()
            file.writeText(WebVtt.shift(raw, opened.timeline.subtitleOffsetMs))
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setId(SUBTITLE_ID)
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(track.lang.ifBlank { null })
            .setLabel(track.title.ifBlank { null })
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    /** Downloads a VTT with the Bearer header, waiting while the server is still extracting it. */
    private suspend fun downloadVtt(url: String): String {
        repeat(SUBTITLE_ATTEMPTS - 1) {
            try {
                return client.subtitleVtt(url)
            } catch (e: ApiException) {
                val wait = e.retryAfterSec ?: throw e
                delay(wait.coerceIn(1, 5) * 1_000)
            }
        }
        return client.subtitleVtt(url)
    }

    /** Shows only the side-loaded subtitle (never tracks embedded in a direct-play MKV). */
    private fun applyTextSelection() {
        if (released) return
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selectedSubtitle == null)
        if (selectedSubtitle != null) {
            val group = player.currentTracks.groups.firstOrNull { group ->
                group.type == C.TRACK_TYPE_TEXT &&
                    (0 until group.length).any { group.getTrackFormat(it).id?.contains(SUBTITLE_ID) == true }
            }
            if (group != null) builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun onSessionLost() {
        session?.sessionLost()
        reopen(currentFileMs())
    }

    /** Playback errors: reopen the stream (a new session) a couple of times, then give up. */
    private fun recover(error: PlaybackException) {
        val current = session
        if (current == null || recoveries >= MAX_RECOVERIES) {
            phase = PlayerPhase.Failed(PlaybackUnavailableException(error))
            return
        }
        recoveries++
        val at = currentFileMs()
        viewModelScope.launch {
            current.suspendSession()
            reopen(at)
        }
    }

    private fun currentFileMs(): Long {
        if (released) return lastFileMs
        val timeline = source?.timeline ?: return lastFileMs
        return timeline.fileMs(player.currentPosition)
    }

    private fun reportedPositionMs(): Long = pendingSeekMs ?: currentFileMs()

    private fun tick() {
        if (released || source == null) return
        val position = currentFileMs()
        lastFileMs = position
        if (pendingSeekMs == null) positionMs = position
        bufferedMs = source?.timeline?.fileMs(player.bufferedPosition) ?: 0L
    }

    private fun buildPlayer(): ExoPlayer {
        // Stream and segment URLs carry ?mk=; the shared client adds Bearer only where there is none.
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

    // endregion

    companion object {
        const val SEEK_STEP_MS = 10_000L
        const val RESUME_PROMPT_MIN_MS = 10_000L
        const val UP_NEXT_SECONDS = 10
        private const val TICK_MS = 500L
        private const val SEEK_SETTLE_MS = 700L
        private const val END_GUARD_MS = 1_000L
        private const val MAX_RECOVERIES = 2
        private const val SUBTITLE_ATTEMPTS = 6
        private const val SUBTITLE_ID = "antresol-subtitle"
    }
}

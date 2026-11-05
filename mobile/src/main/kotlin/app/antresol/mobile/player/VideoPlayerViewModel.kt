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
import androidx.media3.datasource.HttpDataSource
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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

    // Per player, so a closing player never deletes the files of the next one.
    private val subtitleDir = File(context.cacheDir, "subtitles/${UUID.randomUUID()}")
    private var pendingSeekMs: Long? = null
    private var backgrounded = false
    private var pendingReopen: ReopenRequest? = null
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
            // Every pause is saved: the user's, audio focus loss (a call), headphones unplugged.
            if (!playWhenReady) viewModelScope.launch { reporter?.flush() }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A 401 on the stream means the media key died (its session was revoked): fetch a new one.
            val http = error.cause as? HttpDataSource.InvalidResponseCodeException
            if (http?.responseCode == 401) appScope.launch { client.mediaKeys.invalidate() }
            recover(error)
        }

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
        releaseStream()
        val caps = DeviceCaps.current(context, limitToDisplay = false)
        val newSession = VideoSession(client.videoApi(), file.id, caps, quality = defaultQuality)
        session = newSession
        selectedSubtitle = null
        rawSubtitles.clear()
        val opened = openOrStop(newSession) { newSession.open(startMs) }
        val duration = opened.play.durationMs.takeIf { it > 0 } ?: file.durationMs ?: 0L
        durationMs = duration
        reporter = PlaybackReporter(
            api = client.playbackApi(),
            scope = viewModelScope,
            itemId = currentItemId,
            durationMs = duration,
            positionMs = ::reportedPositionMs,
            isPlaying = { !released && player.isPlaying },
            onSessionLost = { lost -> if (lost == session?.session) onSessionLost() },
        ).also { it.start(opened.session) }
        load(opened, opened.timeline.initialPlayerSeekMs, playWhenReady = !backgrounded)
        phase = PlayerPhase.Playing
        // Opened while in the background: give the HLS slot back until the app returns.
        if (backgrounded) onBackground()
    }

    /** Stops the reporter and HLS session of a previous attempt (retry after a failed start). */
    private fun releaseStream() {
        val oldReporter = reporter
        val oldSession = session
        reporter = null
        session = null
        source = null
        val orphan = oldSession?.detachSession()?.takeIf { it != oldReporter?.hlsSession }
        if (oldReporter == null && orphan == null) return
        appScope.launch {
            oldReporter?.stop()
            if (orphan != null && oldSession != null) oldSession.stopSession(orphan)
        }
    }

    /**
     * Runs [open] to completion even if the caller is cancelled: a cancelled HTTP call does not undo
     * `/api/play` on the server. When the result is no longer wanted, its HLS session is stopped so
     * it does not hold one of the profile's two slots.
     */
    private suspend fun openOrStop(target: VideoSession, open: suspend () -> VideoSource): VideoSource {
        val opened = withContext(NonCancellable) { open() }
        if (!currentCoroutineContext().isActive || released || session !== target) {
            target.detachSession()?.let { id -> appScope.launch { target.stopSession(id) } }
            throw CancellationException("Stream no longer wanted")
        }
        return opened
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
        // A growing (EVENT) playlist only covers what the server has produced so far.
        val available = player.duration.takeIf { it != C.TIME_UNSET }
        if (current.timeline.needsNewSession(target, available)) {
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
        if (track.index == selectedAudioIndex || isSwitching) return
        reopen(currentFileMs(), audioIndex = track.index)
    }

    fun selectQuality(value: String) {
        if (value == quality || isSwitching) return
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
            // The final write and stop must finish even if the player closes meanwhile.
            val ending = detachCurrent()
            withContext(NonCancellable) { ending?.stop() }
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

    /** Lets go of the current item's stream; the returned reporter still has to be stopped. */
    private fun detachCurrent(): PlaybackReporter? {
        lastFileMs = currentFileMs()
        val ending = reporter
        reporter = null
        session = null
        source = null
        pendingReopen = null
        player.stop()
        player.clearMediaItems()
        return ending
    }

    // endregion

    // region Lifecycle

    /**
     * The app went to background: pause, save, and give the HLS slot back to the profile. The Up
     * Next countdown stops (the panel waits for the user) and the ended item's stream is closed.
     */
    fun onBackground() {
        backgrounded = true
        if (released) return
        player.pause()
        val upNext = phase as? PlayerPhase.UpNext
        if (upNext != null) {
            upNextJob?.cancel()
            upNextJob = null
            phase = upNext.copy(secondsLeft = null)
            val ending = detachCurrent()
            if (ending != null) appScope.launch { ending.stop() }
            return
        }
        // A reopen in flight suspends the new stream itself once it is loaded.
        if (source == null || isSwitching) return
        val current = session ?: return
        // Detach now, so a quick return (which opens a new session) is never stopped by this.
        val toStop = current.detachSession() ?: return
        if (phase == PlayerPhase.Playing) suspendedAtMs = currentFileMs()
        val active = reporter
        active?.attachSession(null)
        appScope.launch {
            active?.flush()
            current.stopSession(toStop)
        }
    }

    /** Back from background: reopen the suspended HLS stream, paused, where it was. */
    fun onForeground() {
        backgrounded = false
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
        val stream = session
        // A session the reporter does not know (start interrupted before it existed) is stopped too.
        val orphan = stream?.detachSession()?.takeIf { it != ending?.hlsSession }
        finalWrite = appScope.launch {
            ending?.stop()
            if (orphan != null && stream != null) stream.stopSession(orphan)
            withContext(Dispatchers.IO) { subtitleDir.deleteRecursively() }
        }
    }

    /** The final progress write and session stop started when the player closed. */
    var finalWrite: Job? = null
        private set

    // endregion

    // region Streams

    private data class ReopenRequest(val fileMs: Long, val quality: String?, val audioIndex: Int?, val playWhenReady: Boolean)

    /**
     * Opens the stream again at [fileMs] (new session replacing the current one). Requests made while
     * one is in flight are merged and run after it, never cancelling an `/api/play` that may already
     * have created a session on the server.
     */
    private fun reopen(
        fileMs: Long,
        quality: String? = null,
        audioIndex: Int? = null,
        playWhenReady: Boolean = player.playWhenReady,
    ) {
        if (session == null) return
        isSwitching = true
        pendingSeekMs = fileMs
        positionMs = fileMs
        val queued = pendingReopen
        pendingReopen = ReopenRequest(fileMs, quality ?: queued?.quality, audioIndex ?: queued?.audioIndex, playWhenReady)
        if (reopenJob?.isActive == true) return
        reopenJob = viewModelScope.launch {
            try {
                runReopens()
            } finally {
                pendingSeekMs = null
                isSwitching = false
            }
        }
    }

    private suspend fun runReopens() {
        while (true) {
            val request = pendingReopen ?: return
            pendingReopen = null
            val current = session ?: return
            try {
                val opened = openOrStop(current) {
                    current.open(request.fileMs, request.quality ?: current.quality, request.audioIndex ?: current.audioIndex)
                }
                if (pendingReopen != null) continue // Superseded: the next open replaces this session.
                reporter?.attachSession(opened.session)
                val playerMs = if (opened.isHls) (request.fileMs - opened.timeline.startMs).coerceAtLeast(0) else request.fileMs
                load(opened, playerMs, request.playWhenReady && !backgrounded)
                if (backgrounded) onBackground()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (pendingReopen != null) continue
                player.pause()
                // Nothing keeps the old session alive any more; stop it if the server still has it.
                reporter?.attachSession(null)
                current.detachSession()?.let { id -> appScope.launch { current.stopSession(id) } }
                phase = PlayerPhase.Failed(e)
                return
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
        if (phase != PlayerPhase.Playing || backgrounded) return
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
        // Stop keepalives first: a 404 for the stopped session must not start a second reopen.
        reporter?.attachSession(null)
        val broken = current.detachSession()
        if (broken != null) appScope.launch { current.stopSession(broken) }
        reopen(at)
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

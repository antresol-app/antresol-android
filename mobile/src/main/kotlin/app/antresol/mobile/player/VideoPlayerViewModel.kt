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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.model.AudioTrack
import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.FileInfo
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.PlaybackCaps
import app.antresol.core.model.Quality
import app.antresol.core.model.SubTrack
import app.antresol.core.playback.CodecSupport
import app.antresol.core.playback.Episodes
import app.antresol.core.playback.PendingWrites
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

/** `/api/play` answered 429: the profile already streams on as many devices as it may. */
class StreamLimitException(cause: ApiException) : Exception(cause)

/**
 * Plays one movie or episode (and the following episodes) with ExoPlayer, following the spec's
 * video flow: `/api/play` with the device caps, direct or HLS via `?mk=`, HLS keepalive and stop,
 * progress every 10 s and on pause/exit, `replaceSession` for seeks before the HLS start, audio
 * track and quality changes, side-loaded WebVTT subtitles shifted to the HLS start, and recovery
 * when the server drops the session. ExoPlayer lives here so it survives recomposition.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlayerViewModel(
    private val context: Context,
    client: AntresolClient,
    private val appScope: CoroutineScope,
    itemId: Long,
    resume: ResumeMode,
    /** `/api/play` quality for the first open (the user's setting). */
    private val defaultQuality: String,
    /** Start the next episode after the countdown. */
    private val autoplayNext: () -> Boolean,
    /** Final writes and session stops, awaited by screens that show progress. */
    private val pendingWrites: PendingWrites,
) : ViewModel() {
    /**
     * The client for every call of this player (sessions, keepalives, progress, media key, subtitles),
     * pinned to the profile that started it: stops and final writes after a profile switch still count.
     */
    private val client: AntresolClient = client.pinned()

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
    private var healthyJob: Job? = null

    /** HLS stops in flight; a new `/api/play` waits for them, so the profile never briefly holds three sessions. */
    private var stopping: Job? = null

    /** The final write and stop for this player started (the activity is finishing). */
    private var closed = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING
            when (state) {
                Player.STATE_READY -> markHealthyLater()
                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            this@VideoPlayerViewModel.playWhenReady = playWhenReady
            // Every pause saves: the user's, headphones unplugged, audio focus lost to a call.
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
                val saved = if (mode == ResumeMode.FromStart) 0L else savedPositionMs(detail, file)
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

    /**
     * The resume point from `/api/progress`; if that fails, the watched fraction the item came with.
     * Starting at 0 instead would overwrite the server's resume point with the first progress write.
     */
    private suspend fun savedPositionMs(detail: ItemDetail, file: FileInfo): Long = try {
        (Resume.videoStartSec(client.progress(detail.id)) * 1000).toLong()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val fraction = detail.progress.takeIf { it > 0.0 && it < 1.0 } ?: 0.0
        (fraction * (file.durationMs ?: 0L)).toLong()
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
        // Enumerating codecs is slow: keep it off the main thread. A phone scales any decodable video
        // to its screen, so only the decoder limits the height (not the display).
        val caps = withContext(Dispatchers.Default) { DeviceCaps.current(context, limitToDisplay = false) }
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
            onSessionLost = ::onSessionLost,
        ).also { it.start(opened.session) }
        load(opened, opened.timeline.initialPlayerSeekMs, playWhenReady = !backgrounded)
        phase = PlayerPhase.Playing
        // Opened while in the background: give the HLS slot back until the app returns.
        if (backgrounded) suspendStream(resumeAtMs = startMs)
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
        stopInBackground {
            oldReporter?.stop()
            if (orphan != null && oldSession != null) oldSession.stopSession(orphan)
        }
    }

    /**
     * Runs a session stop (or a final write plus stop) on the app scope, so it survives the player,
     * and registers it: the next `/api/play` waits for it and screens refresh after it.
     */
    private fun stopInBackground(block: suspend () -> Unit) {
        val previous = stopping
        val job = appScope.launch {
            previous?.join()
            block()
        }
        stopping = job
        pendingWrites.track(job)
    }

    /**
     * Runs [open] to completion even if the caller is cancelled: a cancelled HTTP call does not undo
     * `/api/play` on the server. When the result is no longer wanted, its HLS session is stopped so
     * it does not hold one of the profile's two slots.
     */
    private suspend fun openOrStop(target: VideoSession, open: suspend () -> VideoSource): VideoSource {
        val opened = withContext(NonCancellable) {
            stopping?.join()
            try {
                open()
            } catch (e: ApiException) {
                throw if (e.isTooManySessions) StreamLimitException(e) else e
            }
        }
        if (!currentCoroutineContext().isActive || released || closed || session !== target) {
            target.detachSession()?.let { id -> stopInBackground { target.stopSession(id) } }
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
        val target = clampTarget(fileMs)
        positionMs = target
        seekJob?.cancel()
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

    /** The duration from `/api/play` or the file, else what the player knows (file time). */
    private fun knownDurationMs(): Long {
        if (durationMs > 0) return durationMs
        val timeline = source?.timeline ?: return 0
        val playerDuration = player.duration
        return if (playerDuration == C.TIME_UNSET) 0 else timeline.fileMs(playerDuration)
    }

    private fun clampTarget(targetMs: Long): Long {
        val duration = knownDurationMs()
        val upper = if (duration > 0) (duration - END_GUARD_MS).coerceAtLeast(0) else Long.MAX_VALUE
        return targetMs.coerceIn(0, upper)
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

    /** "Play Now", "Next Episode" and the end of the Up Next countdown; a second call is ignored. */
    fun playNext() {
        if (phase != PlayerPhase.Playing && phase !is PlayerPhase.UpNext) return
        val next = nextEpisode ?: return
        nextEpisode = null
        upNextJob?.cancel()
        upNextJob = null
        finishCurrent()
        start(next.id, ResumeMode.Resume)
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

    /** Final progress write and session stop for the item that is ending (the next `/api/play` waits for it). */
    private fun finishCurrent() {
        lastFileMs = currentFileMs()
        val ending = reporter
        reporter = null
        session = null
        source = null
        pendingReopen = null
        player.stop()
        player.clearMediaItems()
        if (ending != null) stopInBackground { ending.stop() }
    }

    // endregion

    // region Lifecycle

    /**
     * The app went to background: pause, save, and give the HLS slot back to the profile. A reopen
     * in flight suspends its stream once loaded.
     */
    fun onBackground() {
        backgrounded = true
        if (released || closed) return
        player.pause()
        if (source == null || phase != PlayerPhase.Playing || isSwitching) return
        suspendStream(resumeAtMs = currentFileMs())
    }

    /** Stops keepalives and the HLS session; [onForeground] reopens at [resumeAtMs]. */
    private fun suspendStream(resumeAtMs: Long) {
        val current = session ?: return
        // Detach now, so a quick return (which opens a new session) is never stopped by this.
        val toStop = current.detachSession() ?: return
        suspendedAtMs = resumeAtMs
        val active = reporter
        active?.attachSession(null)
        stopInBackground {
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

    /**
     * The player is closing (the activity finishes): the final progress write and the session stop
     * start now, before the previous screen resumes and refreshes, and are tracked in [pendingWrites].
     */
    fun close() {
        if (closed || released) return
        closed = true
        lastFileMs = currentFileMs()
        player.pause()
        startJob?.cancel()
        reopenJob?.cancel()
        upNextJob?.cancel()
        healthyJob?.cancel()
        pendingReopen = null
        val ending = reporter
        reporter = null
        val stream = session
        session = null
        val orphan = stream?.detachSession()?.takeIf { it != ending?.hlsSession }
        if (ending == null && orphan == null) return
        stopInBackground {
            ending?.stop()
            if (orphan != null && stream != null) stream.stopSession(orphan)
        }
    }

    override fun onCleared() {
        close()
        released = true
        player.removeListener(listener)
        player.release()
        stopInBackground { withContext(Dispatchers.IO) { subtitleDir.deleteRecursively() } }
    }

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
        if (session == null || closed) return
        isSwitching = true
        // The session being replaced gets no more keepalives: a late 404 for it means nothing.
        reporter?.attachSession(null)
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
                // Went to background meanwhile (onBackground skipped the switch): give the slot back now.
                if (backgrounded) suspendStream(resumeAtMs = request.fileMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (pendingReopen != null) continue
                player.pause()
                // Nothing keeps the old session alive any more; stop it if the server still has it.
                reporter?.attachSession(null)
                current.detachSession()?.let { id -> stopInBackground { current.stopSession(id) } }
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

    /** A keepalive got 404 for [lost]. Ignored unless it is the session this player still holds. */
    private fun onSessionLost(lost: String) {
        val current = session ?: return
        if (current.session != lost) return
        current.sessionLost()
        if (phase != PlayerPhase.Playing || backgrounded || reopenJob?.isActive == true) return
        reopen(currentFileMs())
    }

    /** The recovery budget refills only after a stretch of healthy playback, not on every READY. */
    private fun markHealthyLater() {
        healthyJob?.cancel()
        healthyJob = viewModelScope.launch {
            delay(HEALTHY_PLAYBACK_MS)
            recoveries = 0
        }
    }

    /**
     * Playback errors: reopen the stream (a new session) a couple of times, then give up. When the
     * device failed to decode what it claimed to support, the reopen asks for smaller caps, so the
     * server remuxes or transcodes instead of answering with the same direct stream.
     */
    private fun recover(error: PlaybackException) {
        healthyJob?.cancel()
        val current = session
        if (current == null || closed) {
            phase = PlayerPhase.Failed(PlaybackUnavailableException(error))
            return
        }
        val reduced = reducedCaps(current.caps, error)
        if (reduced == null && recoveries >= MAX_RECOVERIES) {
            // Giving up: nothing may keep the broken session (and its transcode) alive.
            detachAndStop(current)
            phase = PlayerPhase.Failed(PlaybackUnavailableException(error))
            return
        }
        if (reduced != null) current.caps = reduced else recoveries++
        val at = currentFileMs()
        detachAndStop(current)
        reopen(at)
    }

    /** Stops keepalives first (a 404 for the stopped session must not start a reopen), then the session. */
    private fun detachAndStop(current: VideoSession) {
        reporter?.attachSession(null)
        val broken = current.detachSession() ?: return
        stopInBackground { current.stopSession(broken) }
    }

    /** Smaller caps after a decoder or audio output failure, or null for other errors (or nothing left to drop). */
    private fun reducedCaps(caps: PlaybackCaps, error: PlaybackException): PlaybackCaps? {
        val format = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat
        val audio = format?.sampleMimeType?.let(MimeTypes::isAudio) ?: false
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            -> CodecSupport.withoutFailingAudio(caps)
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            -> if (audio) CodecSupport.withoutFailingAudio(caps) else CodecSupport.withoutFailingVideo(caps)
            else -> null
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
        private const val HEALTHY_PLAYBACK_MS = 30_000L
        private const val SUBTITLE_ATTEMPTS = 6
        private const val SUBTITLE_ID = "antresol-subtitle"
    }
}

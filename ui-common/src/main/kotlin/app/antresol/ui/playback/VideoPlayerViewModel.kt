package app.antresol.ui.playback

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.api.attempt
import app.antresol.core.family.familyApi
import app.antresol.core.model.AudioTrack
import app.antresol.core.model.AutoSkip
import app.antresol.core.model.AutoSkipRequest
import app.antresol.core.model.FamilyMember
import app.antresol.core.playback.AudioMenu
import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.FileInfo
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.PlayMode
import app.antresol.core.model.PlayResponse
import app.antresol.core.model.PlaybackCaps
import app.antresol.core.model.Progress
import app.antresol.core.model.Quality
import app.antresol.core.model.QualityInfo
import app.antresol.core.model.SubTrack
import app.antresol.core.playback.CodecSupport
import app.antresol.core.playback.Episodes
import app.antresol.core.playback.PendingWrites
import app.antresol.core.playback.PlaybackReporter
import app.antresol.core.playback.PlaybackTiming
import app.antresol.core.playback.Resume
import app.antresol.core.playback.SkipAction
import app.antresol.core.playback.SkipNote
import app.antresol.core.playback.SkipPill
import app.antresol.core.playback.SkipSegments
import app.antresol.core.playback.SkipTracker
import app.antresol.core.playback.SubtitleLanguages
import app.antresol.core.playback.VideoSession
import app.antresol.core.playback.VideoSource
import app.antresol.core.playback.WebVtt
import app.antresol.core.playback.playbackApi
import app.antresol.core.playback.videoApi
import app.antresol.core.ways.PlaybackDisplay
import app.antresol.core.ways.WayOutcome
import app.antresol.core.ways.WayPlayback
import app.antresol.core.ways.WaysController
import app.antresol.core.ways.WaysSource
import app.antresol.core.ways.inputPlaybackApi
import app.antresol.core.ways.inputVideoApi
import app.antresol.core.ways.waysApi
import app.antresol.core.ways.wayPlaybackApi
import app.antresol.ui.R
import app.antresol.ui.family.FamilyModel
import app.antresol.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

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

/** What differs between the TV and the phone player. */
class VideoPlayerOptions(
    /** `/api/play` quality for the first open (the user's setting). */
    val defaultQuality: String = Quality.AUTO,
    /** ISO 639-1 subtitle language to turn on when a video starts, "" for none. */
    val subtitleLanguage: String = "",
    /**
     * Cap the stream height at the display (a TV), or only at the decoder (a phone scales any
     * decodable video to its screen and may be cast or connected to a bigger one).
     */
    val limitToDisplay: Boolean = true,
    /** Start the next episode after the Up Next countdown. */
    val autoplayNext: () -> Boolean = { true },
)

/**
 * Plays one movie or episode (and the following episodes) with ExoPlayer, following the spec's
 * video flow: `/api/play` with the device caps, direct or HLS via `?mk=`, HLS keepalive and stop,
 * progress every 10 s and on every pause/exit, `replaceSession` for seeks before the HLS start, audio
 * track and quality changes, side-loaded WebVTT subtitles shifted to the HLS start, and recovery
 * when the server drops the session or the device fails to decode. ExoPlayer lives here so it
 * survives recomposition. Shared by the TV and phone players; each keeps its own controls.
 *
 * With a [way] (a video an addon serves, see [WayPlayback]) it plays that instead of a library
 * item: an input through the core's input player (the same session, caps, tracks and recovery as a
 * file), a stream the addon serves itself directly, reporting its position to the addon.
 */
@androidx.annotation.OptIn(UnstableApi::class)
open class VideoPlayerViewModel(
    private val context: Context,
    client: AntresolClient,
    private val appScope: CoroutineScope,
    itemId: Long,
    resume: ResumeMode,
    private val options: VideoPlayerOptions,
    /** Final writes and session stops, awaited by screens that show progress. */
    private val pendingWrites: PendingWrites,
    private val way: WayPlayback? = null,
    /** The addons' ways of the session: with it a way's stream offers «Audio & source…» (its own choice again). */
    waysSource: WaysSource? = null,
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

    /** File position shown by the controls; during a pending seek or a preview, its target. */
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

    /**
     * The skip pill of the segment playing (recap, intro, credits; see [SkipTracker]), or null. A
     * [SkipPill.Skip] that is not [SkipPill.Skip.alone] shows only with the controls.
     */
    var skipPill: SkipPill? by mutableStateOf(null)
        private set

    /** A recap or intro skipped by itself («Вступление пропущено · Вернуть»), for a few seconds. */
    var skipNote: SkipNote? by mutableStateOf(null)
        private set

    /** The profile's «Пропускать сам» (`/api/me/playback-prefs`; all off on older servers). */
    var autoSkip: AutoSkip by mutableStateOf(AutoSkip())
        private set

    /** The file has skip segments: the «Пропускать сам» menu is offered. */
    var hasSkipSegments: Boolean by mutableStateOf(false)
        private set

    /**
     * The audio menu: the file's tracks, the voice-over files beside it and the cleaned ones
     * («Очищено», each under its source), as the server lists them (see [AudioMenu.order]).
     */
    val audioTracks: List<AudioTrack> get() = AudioMenu.order(source?.play?.audio.orEmpty())
    val selectedAudioIndex: Int? get() = source?.play?.audioIndex

    /** The audio menu with «Очищенный перевод» as a switch on the chosen voice (see [AudioMenu.cleanedSwitch]). */
    val audioMenu: AudioMenu.CleanedSwitch get() = AudioMenu.cleanedSwitch(source?.play?.audio.orEmpty(), selectedAudioIndex)
    val subtitleTracks: List<SubTrack> get() = source?.play?.subtitles.orEmpty().filter { it.url != null }
    val quality: String get() = source?.play?.quality?.selected?.ifEmpty { null } ?: Quality.AUTO

    /**
     * The quality menu has something to choose. A file or an input of the core: always (the core's qualities). A
     * stream the addon serves itself: only the qualities its answer lists ([wayQualities]).
     */
    val canChooseQuality: Boolean get() = wayDirect == null || wayQualities.isNotEmpty()

    /**
     * The qualities of the stream an addon serves itself, by the addon's own names, that it can play again (two
     * or more, and a `ref` to play one with); empty for anything else. [quality] is the one that plays.
     */
    val wayQualities: List<String> get() = wayDirect?.play?.qualityChoice.orEmpty()

    /** The way being played (what `way` was, then what «Audio & source…» replaced it with); null: a library item. */
    var currentWay: WayPlayback? by mutableStateOf(way)
        private set

    /** The way's own step-by-step choice (a way's stream): its picker is [ways]' `picker`; null for a library item. */
    val ways: WaysController? = if (way?.call != null && waysSource != null) {
        WaysController(this.client.waysApi(), waysSource, viewModelScope, { wayMaxHeight() })
    } else {
        null
    }

    /** The stream can offer «Audio & source…»: the choice of the way that made it. */
    val canChooseSource: Boolean get() = ways != null && currentWay?.call != null

    /**
     * Something besides the server's own lists to show in the audio menu: the tracks of an addon's stream that its
     * answer lists none of (ExoPlayer's own), and «Audio & source…».
     */
    val hasExtraAudioMenu: Boolean get() = canChooseSource || (audioTracks.isEmpty() && streamAudio.size > 1)

    /** The same for the subtitle menu: the subtitles inside an addon's stream, when its answer lists none. */
    val hasExtraSubtitleMenu: Boolean get() = subtitleTracks.isEmpty() && streamText.isNotEmpty()

    /** The audio tracks inside the stream an addon serves (ExoPlayer's); [AudioTrack.index] is their order. */
    var streamAudio: List<AudioTrack> by mutableStateOf(emptyList())
        private set

    /** The order of the one of [streamAudio] that plays, or -1. */
    var streamAudioSelected: Int by mutableIntStateOf(-1)
        private set

    /** The subtitles inside the stream an addon serves (ExoPlayer's), [SubTrack.index] their order. */
    var streamText: List<SubTrack> by mutableStateOf(emptyList())
        private set

    /** The order of the one of [streamText] that shows, or -1. */
    var streamTextSelected: Int by mutableIntStateOf(-1)
        private set

    /** A sentence to show briefly (what a way said, or why the choice of one failed); the screen clears it. */
    var noticeText: String? by mutableStateOf(null)

    private var currentItemId = itemId

    /** «Семья»: where the other profiles are in the item that plays (a library item; an addon's stream is none). */
    val family = FamilyModel(client.familyApi(), viewModelScope)

    /** The profiles that are somewhere in the item that plays (not finished, with a position): the «Семья» menu lists them. */
    val familyHere: List<FamilyMember> get() = family.members.filter { it.itemId == currentItemId && !it.finished && it.positionMs > 0 }

    fun familyPhoto(m: FamilyMember): String? = client.urls.image(m.photo)

    /**
     * Continues from [m]'s place: their position becomes this profile's own first (never the other way round), then the
     * player goes there: a seek in this item, or the next episode / another episode from its start / its saved place.
     */
    fun jumpToFamily(m: FamilyMember) = family.jump(m) { place ->
        if (place.itemId == currentItemId && !place.next) {
            seekTo(place.positionMs)
        } else {
            finishCurrent()
            start(place.itemId, if (place.next) ResumeMode.FromStart else ResumeMode.Resume)
        }
    }

    private var wayDirect: WayPlayback.Direct? by mutableStateOf(null)

    /** The quality picked in the menu of an addon's stream: kept when its link is made again (a stale one). */
    private var wayQuality: String? = null
    private var streamAudioChoice: Int? = null
    private var streamTextChoice: Int? = null
    private var endedPlayback = false
    private var pendingFile: FileInfo? = null
    private var session: VideoSession? = null
    private var reporter: PlaybackReporter? = null
    private val rawSubtitles = HashMap<String, String>()
    private var skip: SkipTracker? = null
    private var skipNoteJob: Job? = null

    // Per player, so a closing player never deletes the files of the next one.
    private val subtitleDir = File(context.cacheDir, "subtitles/${UUID.randomUUID()}")
    private var pendingSeekMs: Long? = null

    /** A target the controls show without seeking yet (TV scrubbing); the position ticks do not move it. */
    private var previewMs: Long? = null
    private var backgrounded = false
    private var pendingReopen: ReopenRequest? = null
    private var lastFileMs = 0L
    private var suspendedAtMs: Long? = null
    private var recoveries = 0
    private var released = false
    private var startJob: Job? = null
    private var reopenJob: Job? = null
    private var switchJob: Job? = null
    private var seekJob: Job? = null
    private var subtitleJob: Job? = null
    private var upNextJob: Job? = null
    private var healthyJob: Job? = null

    /** HLS stops in flight; a new `/api/play` waits for them, so the profile never briefly holds three sessions. */
    private var stopping: Job? = null

    /** The final write and stop for this player started ([close]). */
    private var closed = false

    /** The position clock: runs only while the video plays or loads to play in front (see [syncTicker]). */
    private val ticker = Ticker(viewModelScope, TICK_MS, ::tick)

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        // Any change (play, pause, a seek, buffering) goes through here once the other callbacks are done.
        override fun onEvents(player: Player, events: Player.Events) = syncTicker()

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
            if (error.isUnauthorized) {
                // The media key died: its device session was revoked or deleted. Drop the key, and let
                // an authenticated call report the 401 so the app returns to the profile picker.
                appScope.launch {
                    this@VideoPlayerViewModel.client.mediaKeys.invalidate()
                    attempt { this@VideoPlayerViewModel.client.me() }
                }
            }
            recover(error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            applyTextSelection()
            applyAudioSelection()
            applyStreamAudio()
            refreshStreamTracks(tracks)
        }
    }

    init {
        player.addListener(listener)
        if (way != null) startWay(way) else start(itemId, resume)
        ways?.let { controller -> viewModelScope.launch { controller.outcomes.collect(::onWayOutcome) } }
        loadAutoSkip()
    }

    // region Starting

    private fun start(itemId: Long, mode: ResumeMode) {
        startJob?.cancel()
        phase = PlayerPhase.Loading
        startJob = viewModelScope.launch {
            try {
                // Neither depends on the item's details, so both start alongside that request: the
                // codec list (kept after its first use) and the resume point of [itemId] itself.
                launch(Dispatchers.Default) { runCatching { DeviceCaps.current(context, limitToDisplay = options.limitToDisplay) } }
                val early: Deferred<Result<Progress>>? = if (mode == ResumeMode.FromStart) {
                    null
                } else {
                    async {
                        try {
                            Result.success(client.progress(itemId))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                }
                var detail = client.item(itemId)
                if (detail.kind == Kind.SERIES || detail.kind == Kind.SEASON) {
                    val episode = Episodes.toPlay(detail) ?: throw PlaybackUnavailableException()
                    detail = client.item(episode.id)
                }
                val file = detail.files.firstOrNull() ?: throw PlaybackUnavailableException()
                item = detail
                currentItemId = detail.id
                family.load(detail.id)
                findNextEpisode(detail)
                // A series or season plays one of its episodes: the early answer is not that episode's.
                val asked = early?.takeIf { detail.id == itemId }
                if (asked == null) early?.cancel()
                val saved = when {
                    mode == ResumeMode.FromStart -> 0L
                    asked != null -> savedPositionMs(detail, file) { asked.await().getOrThrow() }
                    else -> savedPositionMs(detail, file)
                }
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
    private suspend fun savedPositionMs(
        detail: ItemDetail,
        file: FileInfo,
        progress: suspend () -> Progress = { client.progress(detail.id) },
    ): Long = try {
        (Resume.videoStartSec(progress()) * 1000).toLong()
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

    private suspend fun begin(file: FileInfo, startMs: Long) = beginStream(
        startMs = startMs,
        fallbackDurationMs = file.durationMs ?: 0L,
        reportApi = client.playbackApi(),
        reportItemId = currentItemId,
        newSession = { caps -> VideoSession(client.videoApi(), file.id, caps, quality = options.defaultQuality) },
    )

    /**
     * Opens a stream through a [VideoSession] built by [newSession] (a library file, or an input of
     * the core's input player), reports to [reportApi], and plays it from [startMs].
     */
    private suspend fun beginStream(
        startMs: Long,
        fallbackDurationMs: Long,
        reportApi: app.antresol.core.playback.PlaybackApi,
        reportItemId: Long,
        newSession: (PlaybackCaps) -> VideoSession,
    ) {
        releaseStream()
        // Enumerating codecs can take hundreds of ms on a slow device: keep it off the main thread.
        val caps = withContext(Dispatchers.Default) { DeviceCaps.current(context, limitToDisplay = options.limitToDisplay) }
        val newSession = newSession(caps)
        session = newSession
        selectedSubtitle = null
        rawSubtitles.clear()
        val opened = openOrStop(newSession) { newSession.open(startMs) }
        val duration = opened.play.durationMs.takeIf { it > 0 } ?: fallbackDurationMs
        durationMs = duration
        reporter = PlaybackReporter(
            api = reportApi,
            scope = viewModelScope,
            itemId = reportItemId,
            durationMs = duration,
            positionMs = ::reportedPositionMs,
            isPlaying = { !released && player.isPlaying },
            onSessionLost = ::onSessionLost,
        ).also { it.start(opened.session) }
        preselectSubtitle(opened)
        resetSkip(SkipTracker(opened.play.segments))
        load(opened, opened.timeline.initialPlayerSeekMs, playWhenReady = !backgrounded)
        phase = PlayerPhase.Playing
        // Opened while in the background: give the HLS slot back until the app returns.
        if (backgrounded) suspendStream(resumeAtMs = startMs)
    }

    // region Ways

    private fun startWay(way: WayPlayback) {
        startJob?.cancel()
        phase = PlayerPhase.Loading
        currentWay = way
        item = way.display.toItem(context)
        startJob = viewModelScope.launch {
            try {
                when (way) {
                    is WayPlayback.Input -> startInput(way)
                    is WayPlayback.Direct -> openDirect(way, startMs = (way.play.startSec * 1000).toLong())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = PlayerPhase.Failed(e)
            }
        }
    }

    /**
     * «Audio & source…»: opens the choice of the way that made this stream, step by step ([ways]' picker), from the
     * position the video is at, so what it picks resumes there ([onWayOutcome]). Nothing happens without a way.
     */
    fun openSourceChoice() {
        val controller = ways ?: return
        val call = currentWay?.call ?: return
        if (phase != PlayerPhase.Playing) return
        controller.start(call.copy(startSec = currentFileMs() / 1000.0), choose = true)
    }

    /** What the choice made: a stream replaces this one where it is; a sentence or a failure is shown. */
    private fun onWayOutcome(outcome: WayOutcome) {
        when (outcome) {
            is WayOutcome.Play -> switchWay(outcome.playback)
            is WayOutcome.Done -> noticeText = outcome.text
            is WayOutcome.Failed -> noticeText = outcome.error.userMessage(context.resources)
        }
    }

    /**
     * Plays [next] (another source, another voice-over: what the way's choice made) in place of the stream that
     * plays, from where that one is. The old one is saved and let go as the new one opens.
     */
    private fun switchWay(next: WayPlayback) {
        if (closed || released) return
        val at = currentFileMs()
        currentWay = next
        streamAudioChoice = null
        streamTextChoice = null
        wayQuality = null
        isSwitching = true
        player.pause()
        startJob?.cancel()
        reopenJob?.cancel()
        switchJob?.cancel()
        switchJob = viewModelScope.launch {
            try {
                when (next) {
                    is WayPlayback.Input -> startInput(next, startAtMs = at)
                    is WayPlayback.Direct -> openDirect(next, startMs = at)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = PlayerPhase.Failed(e)
            } finally {
                isSwitching = false
            }
        }
    }

    /** The tallest picture this device shows, for a way that picks a quality by it (null: no limit). */
    private suspend fun wayMaxHeight(): Int? = withContext(Dispatchers.Default) {
        runCatching { DeviceCaps.current(context, limitToDisplay = options.limitToDisplay).maxHeight }.getOrNull()
    }

    /** The core's input player: the same session as a file's, with the file's caps, tracks and quality. */
    private suspend fun startInput(input: WayPlayback.Input, startAtMs: Long? = null) {
        wayDirect = null
        val startMs = startAtMs ?: (client.inputInfo(input.id).startSec * 1000).toLong()
        beginStream(
            startMs = startMs,
            fallbackDurationMs = 0L,
            reportApi = client.inputPlaybackApi(input.id),
            reportItemId = 0L,
            newSession = { caps -> VideoSession(client.inputVideoApi(input.id), 0L, caps, quality = options.defaultQuality) },
        )
    }

    /**
     * A stream the addon serves itself: its URL (and its subtitles) get the media key, the position
     * goes to the addon every few seconds, and the addon's session is closed with the player.
     */
    private suspend fun openDirect(direct: WayPlayback.Direct, startMs: Long) {
        releaseStream()
        val play = direct.play
        wayDirect = direct
        endedPlayback = false
        selectedSubtitle = null
        rawSubtitles.clear()
        val key = client.mediaKeys.get(Duration.ofMillis((play.durationMs - startMs).coerceAtLeast(0) + DIRECT_KEY_MARGIN_MS))
        val subtitles = play.subtitles.filter { it.url.isNotBlank() }.map { track ->
            SubTrack(
                index = track.index,
                codec = track.codec,
                lang = track.lang,
                title = track.label.ifBlank { track.title },
                external = true,
                url = client.urls.playback(track.url, key),
            )
        }
        // Played like a file the server does not transcode: the player's position is the file's.
        val response = PlayResponse(
            mode = PlayMode.DIRECT,
            url = play.url,
            startSec = startMs / 1000.0,
            durationMs = play.durationMs,
            subtitles = subtitles,
            quality = QualityInfo(selected = play.quality.current, applied = play.quality.current),
        )
        val opened = VideoSource(response, client.urls.playback(play.url, key), playlist = play.isPlaylist)
        durationMs = play.durationMs
        reporter = PlaybackReporter(
            api = client.wayPlaybackApi(direct.addonId, direct.request, play.session) { endedPlayback },
            scope = viewModelScope,
            itemId = 0L,
            durationMs = play.durationMs,
            positionMs = ::reportedPositionMs,
            isPlaying = { !released && player.isPlaying },
            timing = PlaybackTiming(progressInterval = play.progress.every.coerceAtLeast(MIN_PROGRESS_EVERY_SEC).seconds),
        ).also { it.start(play.session.takeIf { id -> id.isNotBlank() }) }
        preselectSubtitle(opened)
        resetSkip(null)
        load(opened, opened.timeline.initialPlayerSeekMs, playWhenReady = !backgrounded)
        phase = PlayerPhase.Playing
    }

    /**
     * The media link of a direct stream went stale (410), or «Try Again» after a failure: run the
     * way again with `refresh` and carry on from where it was.
     */
    private fun reopenDirect(direct: WayPlayback.Direct, quality: String? = null) {
        val at = currentFileMs()
        isSwitching = true
        player.pause()
        reopenJob?.cancel()
        reopenJob = viewModelScope.launch {
            try {
                // The ref the last answer gave plays the same stream again, in the quality chosen in the menu
                // (kept for the next stale link); an answer without one is run as it was asked.
                val ref = direct.play.ref?.takeIf { it.isNotBlank() }
                val chosen = (quality ?: wayQuality)?.takeIf { it in direct.play.qualityChoice }
                val request = direct.request.copy(
                    ref = ref ?: direct.request.ref,
                    quick = if (ref != null) null else direct.request.quick,
                    quality = if (ref != null) chosen else null,
                    refresh = true,
                    startSec = at / 1000.0,
                )
                val play = client.wayRun(direct.addonId, request).play ?: throw PlaybackUnavailableException()
                wayQuality = chosen
                openDirect(direct.copy(play = play), startMs = at)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = PlayerPhase.Failed(e)
            } finally {
                isSwitching = false
            }
        }
    }

    /** A 410 from the media server: its link is stale. */
    private fun PlaybackException.isStaleMedia(): Boolean =
        generateSequence<Throwable>(this) { it.cause }.any { it is HttpDataSource.InvalidResponseCodeException && it.responseCode == HTTP_GONE }

    // endregion

    /** Stops the reporter and HLS session of a previous attempt (retry after a failed start). */
    private fun releaseStream() {
        val oldReporter = reporter
        val oldSession = session
        reporter = null
        session = null
        source = null
        resetSkip(null)
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

    /** Whether seeks are possible now (a stream is loaded and not being reopened). */
    protected val canSeek: Boolean get() = source != null && !isSwitching

    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) return
        player.playWhenReady = !player.playWhenReady
    }

    /** Seeks by [deltaMs] on the file timeline (skip buttons, double taps, remote keys). */
    fun seekBy(deltaMs: Long) {
        if (!canSeek) return
        seekTo((pendingSeekMs ?: currentFileMs()) + deltaMs)
    }

    /** Seeks to [fileMs] on the file timeline; before the HLS start this reopens the stream. */
    fun seekTo(fileMs: Long) {
        previewMs = null
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

    /** Shows [fileMs] (clamped) as the position without seeking; [seekTo] commits it. Returns the shown target. */
    protected fun preview(fileMs: Long): Long {
        val target = clampTarget(fileMs)
        previewMs = target
        positionMs = target
        return target
    }

    /** Where a relative move starts: the preview, a pending seek, or the playing position. */
    protected fun targetBaseMs(): Long = previewMs ?: pendingSeekMs ?: currentFileMs()

    /** The duration from `/api/play` or the file, else what the player knows (file time). */
    protected fun knownDurationMs(): Long {
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

    /** Plays [track]: a new stream at the current position (external tracks carry their `src`). */
    fun selectAudio(track: AudioTrack) {
        if (track.index == selectedAudioIndex) return
        reopen(currentFileMs(), audioIndex = track.index, audioSrc = track.src.ifEmpty { null })
    }

    fun selectQuality(value: String) {
        if (value == quality) return
        // A stream an addon serves: one of its own names, the addon plays it again from where this one is.
        wayDirect?.let { direct ->
            if (value in wayQualities) reopenDirect(direct, quality = value)
            return
        }
        reopen(currentFileMs(), quality = value)
    }

    /** Turns on the subtitle language chosen in the settings, if the video has it. */
    private suspend fun preselectSubtitle(opened: VideoSource) {
        val track = SubtitleLanguages.preferred(opened.play.subtitles, options.subtitleLanguage) ?: return
        val url = track.url ?: return
        try {
            rawSubtitles[url] = downloadVtt(url)
            selectedSubtitle = track
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = R.string.player_subtitles_failed
        }
    }

    fun selectSubtitle(track: SubTrack?) {
        subtitleJob?.cancel()
        streamTextChoice = null
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
                notice = R.string.player_subtitles_failed
            }
        }
    }

    fun retry() {
        val direct = wayDirect
        if (direct != null) {
            phase = PlayerPhase.Playing
            reopenDirect(direct)
            return
        }
        val playing = currentWay
        if (playing != null && session == null) {
            startWay(playing)
            return
        }
        if (session != null && source != null) {
            phase = PlayerPhase.Playing
            reopen(lastFileMs)
        } else {
            start(currentItemId, ResumeMode.Resume)
        }
    }

    // endregion

    // region Episodes

    /** "Cancel" on the Up Next prompt: stay on the last frame with the controls. */
    fun cancelUpNext() {
        if (phase !is PlayerPhase.UpNext) return
        upNextJob?.cancel()
        upNextJob = null
        phase = PlayerPhase.Playing
    }

    /** "Play Now", "Next Episode", the remote's Next key and the end of the countdown; a second call is ignored. */
    fun playNext() {
        // Loading (a second press) is ignored: it would skip another episode.
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
        endedPlayback = true
        viewModelScope.launch { reporter?.flush() }
        val next = nextEpisode
        if (next == null) {
            phase = PlayerPhase.Finished
            return
        }
        startUpNext(next)
    }

    /** Shows Up Next for [next]; with autoplay on, it counts down and then plays it. */
    private fun startUpNext(next: EpisodeCard) {
        upNextJob?.cancel()
        upNextJob = null
        if (!options.autoplayNext()) {
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
        resetSkip(null)
        pendingReopen = null
        player.stop()
        player.clearMediaItems()
        if (ending != null) stopInBackground { ending.stop() }
    }

    // endregion

    // region Skip segments

    /** Runs the action of the pill on screen, [skipPill] (a tap, or OK on the TV). */
    fun pressSkip() {
        when (val pill = skipPill ?: return) {
            is SkipPill.Next -> if (skip?.takeNext() == true) playNext()
            is SkipPill.Skip -> runSkip(pill.action)
        }
    }

    /** «Смотреть титры»: no «Следующая серия» and no automatic next for this file. */
    fun watchCredits() {
        skip?.creditsDismissed = true
        skipPill = null
    }

    /** «Вернуть» on the note of an automatic skip: back to where the segment started. */
    fun undoSkip() {
        val note = skipNote ?: return
        skipNote = null
        skipNoteJob?.cancel()
        seekTo(note.backMs)
    }

    /** «Пропускать сам»: [value] for this player at once, and the profile's on the server (only what changed). */
    fun changeAutoSkip(value: AutoSkip) {
        val old = autoSkip
        if (value == old) return
        autoSkip = value
        val request = AutoSkipRequest(
            recap = value.recap.takeIf { it != old.recap },
            intro = value.intro.takeIf { it != old.intro },
            credits = value.credits.takeIf { it != old.credits },
        )
        appScope.launch {
            try {
                client.setAutoSkip(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An older server or a locked kid profile: the choice holds for this player only.
            }
        }
    }

    private fun loadAutoSkip() {
        viewModelScope.launch {
            try {
                autoSkip = client.playbackPrefs().autoSkip
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No prefs: nothing is skipped by itself.
            }
        }
    }

    private fun resetSkip(tracker: SkipTracker?) {
        skip = tracker
        hasSkipSegments = tracker?.hasSegments == true
        skipPill = null
        skipNote = null
        skipNoteJob?.cancel()
    }

    /** On a position tick: the pill of the segment playing, and what «Пропускать сам» does by itself. */
    private fun updateSkip(fileMs: Long) {
        val tracker = skip ?: return
        if (phase != PlayerPhase.Playing || isSwitching || pendingSeekMs != null || previewMs != null) return
        val step = tracker.step(
            timeSec = fileMs / 1000.0,
            nowMs = SystemClock.uptimeMillis(),
            playing = player.isPlaying,
            durationSec = knownDurationMs() / 1000.0,
            hasNext = nextEpisode != null,
            autoplayNext = options.autoplayNext(),
            autoSkip = autoSkip,
        )
        skipPill = step.pill
        when (val auto = step.auto) {
            is SkipTracker.Auto.Skipped -> {
                showSkipNote(auto.note)
                seekTo(auto.toMs)
            }
            SkipTracker.Auto.Next -> playNext()
            SkipTracker.Auto.End -> runSkip(SkipAction.End)
            null -> Unit
        }
    }

    private fun runSkip(action: SkipAction) {
        when (action) {
            is SkipAction.Seek -> seekTo(action.toMs)
            // Played on to the end (its last second): the end of the file, Up Next or the end of the film.
            SkipAction.End -> seekTo(knownDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE)
            SkipAction.Next -> playNext()
        }
        skipPill = null
    }

    private fun showSkipNote(note: SkipNote) {
        skipNote = note
        skipNoteJob?.cancel()
        skipNoteJob = viewModelScope.launch {
            delay(SkipSegments.SKIPPED_NOTE_MS)
            skipNote = null
        }
    }

    // endregion

    // region Lifecycle

    /**
     * The app went to background: pause, save, and give the HLS slot back to the profile. The Up
     * Next countdown stops (it restarts on return). A reopen in flight suspends its stream once loaded.
     */
    fun onBackground() {
        backgrounded = true
        if (released || closed) return
        Log.i(PLAYER_LOG, "background at ${currentFileMs()} ms, phase $phase")
        player.pause()
        if (source == null || isSwitching) return
        if (phase is PlayerPhase.UpNext) {
            upNextJob?.cancel()
            upNextJob = null
        }
        // Only a playing stream reopens on return; at Up Next or after an error there is nothing to resume.
        suspendStream(resumeAtMs = if (phase == PlayerPhase.Playing) currentFileMs() else null)
    }

    /** Back from background: restart the Up Next countdown, or reopen the suspended stream, paused, where it was. */
    fun onForeground() {
        backgrounded = false
        (phase as? PlayerPhase.UpNext)?.let { if (it.secondsLeft != null && upNextJob?.isActive != true) startUpNext(it.episode) }
        val at = suspendedAtMs ?: return
        suspendedAtMs = null
        Log.i(PLAYER_LOG, "foreground: reopening at $at ms")
        reopen(at, playWhenReady = false)
    }

    /** Stops keepalives and the HLS session; [resumeAtMs] is where [onForeground] reopens (null: it does not). */
    private fun suspendStream(resumeAtMs: Long?) {
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

    /**
     * The player is closing: the final progress write and the session stop start now (before the
     * previous screen resumes and refreshes) and are tracked in [pendingWrites]. Called by the
     * screen when it finishes, and at the latest when the ViewModel is cleared.
     */
    fun close() {
        if (closed || released) return
        closed = true
        lastFileMs = currentFileMs()
        player.pause()
        startJob?.cancel()
        reopenJob?.cancel()
        switchJob?.cancel()
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

    private data class ReopenRequest(
        val fileMs: Long,
        val quality: String?,
        val audioIndex: Int?,
        val audioSrc: String?,
        val playWhenReady: Boolean,
    )

    /**
     * Opens the stream again at [fileMs] (new session replacing the current one). Requests made while
     * one is in flight are merged and run after it, never cancelling an `/api/play` that may already
     * have created a session on the server.
     */
    private fun reopen(
        fileMs: Long,
        quality: String? = null,
        audioIndex: Int? = null,
        audioSrc: String? = null,
        playWhenReady: Boolean = player.playWhenReady,
    ) {
        if (session == null || closed) return
        isSwitching = true
        // The session being replaced gets no more keepalives: a late 404 for it means nothing.
        reporter?.attachSession(null)
        pendingSeekMs = fileMs
        positionMs = fileMs
        val queued = pendingReopen
        pendingReopen = if (audioIndex != null) {
            ReopenRequest(fileMs, quality ?: queued?.quality, audioIndex, audioSrc, playWhenReady)
        } else {
            ReopenRequest(fileMs, quality ?: queued?.quality, queued?.audioIndex, queued?.audioSrc, playWhenReady)
        }
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
                    if (request.audioIndex != null) {
                        current.open(request.fileMs, request.quality ?: current.quality, request.audioIndex, request.audioSrc)
                    } else {
                        current.open(request.fileMs, request.quality ?: current.quality)
                    }
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
            .apply { if (opened.playlist) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(listOfNotNull(subtitle))
            .build()
        // The audio override of a previous direct stream refers to tracks of another file.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
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
        // The subtitles inside a stream an addon serves are offered only when its answer lists none (then it is the choice).
        val own = streamTextChoice?.takeIf { selectedSubtitle == null && wayDirect != null }
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selectedSubtitle == null && own == null)
        if (selectedSubtitle != null) {
            val group = player.currentTracks.groups.firstOrNull { group ->
                group.type == C.TRACK_TYPE_TEXT &&
                    (0 until group.length).any { group.getTrackFormat(it).id?.contains(SUBTITLE_ID) == true }
            }
            if (group != null) builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
        } else if (own != null) {
            ownGroups(player.currentTracks, C.TRACK_TYPE_TEXT).getOrNull(own)?.let { builder.addOverride(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun ownGroups(tracks: Tracks, type: Int): List<Tracks.Group> = OwnTracks.groups(tracks, type, SUBTITLE_ID)

    /** Plays the audio track of the stream an addon serves that was picked in the menu, as the player re-reads its tracks. */
    private fun applyStreamAudio() {
        if (released || wayDirect == null) return
        val wanted = streamAudioChoice ?: return
        val group = ownGroups(player.currentTracks, C.TRACK_TYPE_AUDIO).getOrNull(wanted) ?: return
        if (group.isSelected) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    /** What the stream an addon serves holds itself (audio and subtitles), for the menus when its answer lists none. */
    private fun refreshStreamTracks(tracks: Tracks) {
        if (wayDirect == null) {
            streamAudio = emptyList()
            streamText = emptyList()
            streamAudioSelected = -1
            streamTextSelected = -1
            return
        }
        val audio = ownGroups(tracks, C.TRACK_TYPE_AUDIO)
        streamAudio = OwnTracks.audio(audio)
        streamAudioSelected = OwnTracks.selected(audio)
        val text = ownGroups(tracks, C.TRACK_TYPE_TEXT)
        streamText = OwnTracks.text(text)
        streamTextSelected = OwnTracks.selected(text)
    }

    /** Plays [track] of [streamAudio]: the stream itself switches, nothing is asked of the server. */
    fun selectStreamAudio(track: AudioTrack) {
        streamAudioChoice = track.index
        applyStreamAudio()
    }

    /** Shows [track] of [streamText], or none ([track] null). */
    fun selectStreamText(track: SubTrack?) {
        subtitleJob?.cancel()
        selectedSubtitle = null
        streamTextChoice = track?.index
        applyTextSelection()
    }

    /**
     * Direct play keeps ExoPlayer's own audio choice from following the server's: select the audio
     * track the server reports as playing (`audioIndex`, by its order among the file's audio streams).
     */
    private fun applyAudioSelection() {
        if (released) return
        val play = source?.play ?: return
        if (play.isHls) return
        val wanted = play.audioIndex ?: return
        // Voice-over files beside the video are never in a direct stream: only the file's own count.
        val ordinal = play.audio.filter { it.index < AudioTrack.EXTERNAL_BASE }.sortedBy { it.index }.indexOfFirst { it.index == wanted }
        val group = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(ordinal) ?: return
        if (group.isSelected || !group.isTrackSupported(0)) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
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
        wayDirect?.let { direct ->
            if (closed) return
            if (error.isStaleMedia() && recoveries < MAX_RECOVERIES) {
                recoveries++
                reopenDirect(direct)
            } else {
                phase = PlayerPhase.Failed(PlaybackUnavailableException(error))
            }
            return
        }
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

    /**
     * The clock ticks while the video plays, buffers to play or loads, and the app is in front. Otherwise
     * (paused with a full buffer, ended, in the background, closing) it sleeps and any change reads the position once,
     * so a paused seek or the final position of a pause is still shown.
     */
    private fun syncTicker() {
        ticker.set(!backgrounded && !released && !closed && videoClockRuns(player.playWhenReady, player.playbackState, player.isLoading))
        if (!ticker.running) tick()
    }

    private fun tick() {
        if (released || source == null) return
        val position = currentFileMs()
        lastFileMs = position
        if (pendingSeekMs == null && previewMs == null) positionMs = position
        bufferedMs = source?.timeline?.fileMs(player.bufferedPosition) ?: 0L
        updateSkip(position)
    }

    private fun buildPlayer(): ExoPlayer = buildVideoPlayer(context, client)

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
        private const val HTTP_GONE = 410
        private const val DIRECT_KEY_MARGIN_MS = 60 * 60 * 1000L
        private const val MIN_PROGRESS_EVERY_SEC = 3
    }
}

/** The item the player screens show for a video of a way: its title, and «S1 · E3» for an episode. */
private fun PlaybackDisplay.toItem(context: Context): ItemDetail {
    val episode = if (season != null && episode != null) {
        context.getString(R.string.way_episode_code, season, episode)
    } else {
        ""
    }
    return ItemDetail(
        id = 0,
        kind = if (episode.isNotEmpty()) Kind.EPISODE else Kind.MOVIE,
        title = title,
        subtitle = episode,
        poster = poster,
        backdrop = backdrop,
    )
}

/**
 * The logcat tag of the video players: their lifecycle and ExoPlayer's [EventLogger] (decoders,
 * surface sizes, first frames, errors), so `adb logcat -s AntresolPlayer` tells what a device did.
 */
const val PLAYER_LOG = "AntresolPlayer"

/**
 * The ExoPlayer of the app's video players (films, episodes, trailers, live): media through [client]'s
 * HTTP client, decoder fallback, a new decoder for a new surface ([FreshCodecVideoRenderer]), movie
 * audio focus, paused when headphones are unplugged, logged under [PLAYER_LOG].
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun buildVideoPlayer(context: Context, client: AntresolClient): ExoPlayer {
    // Stream and segment URLs carry ?mk=; the shared client adds Bearer only where there is none.
    val http = OkHttpDataSource.Factory(client.httpClient)
    val dataSources = DefaultDataSource.Factory(context, http)
    return ExoPlayer.Builder(context)
        .setRenderersFactory(VideoRenderersFactory(context))
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
        .setLoadControl(videoLoadControl(context))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { it.addAnalyticsListener(EventLogger(PLAYER_LOG)) }
        .also { PlayerCensus.attach(it, PlayerCensus.VIDEO) }
}

/**
 * ExoPlayer's default load control, with the buffer's size bound by the app's heap: the default holds
 * up to about 138 MB of a high-bitrate file in the Java heap, more than most devices give an app.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun videoLoadControl(context: Context): LoadControl {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    val bytes = videoBufferBytes(manager.isLowRamDevice, memory.totalMem, manager.memoryClass) ?: return DefaultLoadControl()
    return DefaultLoadControl.Builder().setTargetBufferBytes(bytes).setPrioritizeTimeOverSizeThresholds(false).build()
}

/**
 * The video buffer's size: a third of the heap [memoryClassMb] on every device, since the buffer is
 * `byte[]` in that heap whatever memory the device has (a 3 GB TV box gives an app 128–192 MB and
 * plays a 60–80 Mbit/s remux). At least [MIN_BUFFER_MB] (under it nothing of such a file is left to
 * play from: 16 MB is 2 s at 60 Mbit/s; a heap that makes this more than a third is 48 MB or less),
 * and null (ExoPlayer's default) where a third is the default's [DEFAULT_BUFFER_MB] or more. A
 * low-RAM device (the system says so, or it has at most [LOW_RAM_TOTAL_BYTES] of memory) gets no
 * more than [LOW_RAM_MAX_BUFFER_MB] whatever its heap.
 *
 * Heap → buffer (at 60 / 80 Mbit/s): 48 → 16 MB (2.2 / 1.7 s), 64 → 21 (2.9 / 2.2 s), 128 → 42
 * (5.9 / 4.4 s), 192 → 64 (8.9 / 6.7 s), 256 → 85 (11.9 / 8.9 s; low-RAM 64), 512 → the default
 * 138 (19 / 14 s; low-RAM 64).
 */
internal fun videoBufferBytes(lowRamDevice: Boolean, totalRamBytes: Long, memoryClassMb: Int): Int? {
    val lowRam = lowRamDevice || totalRamBytes <= LOW_RAM_TOTAL_BYTES
    val megabytes = (memoryClassMb / 3).coerceAtLeast(MIN_BUFFER_MB).let { if (lowRam) minOf(it, LOW_RAM_MAX_BUFFER_MB) else it }
    return if (megabytes >= DEFAULT_BUFFER_MB) null else megabytes * 1024 * 1024
}

/**
 * Must stay above 10 MB. `DefaultLoadControl` (Media3 1.5.1) stops loading once the target is
 * allocated however little is buffered in time, and a track that got no sample by then leaves its
 * renderer not ready: «Playback stuck buffering and not loading» 4 s later. An MP4 cannot get
 * there with more than 10 MB: `Mp4Extractor` reads no track further than that ahead of another
 * (`MAXIMUM_READ_AHEAD_BYTES_STREAM`). Matroska is read as it lies, with no such bound: a file
 * whose sound lies more than the target behind its picture stalls with any target (16 MB is 1.6 s
 * at 80 Mbit/s, 6.4 s at 20; ExoPlayer's own 138 MB is 13.8 s at 80); muxers interleave within a second.
 */
private const val MIN_BUFFER_MB = 16
private const val LOW_RAM_MAX_BUFFER_MB = 64

/** `DefaultLoadControl`'s own target for a video with sound: 2000 + 200 segments of 64 KB, and a little for text. */
private const val DEFAULT_BUFFER_MB = 138

/** A device sold as «2 GB» reports a little less than that. */
private const val LOW_RAM_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

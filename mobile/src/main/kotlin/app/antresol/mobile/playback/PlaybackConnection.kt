package app.antresol.mobile.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Chapter
import app.antresol.core.model.Track
import app.antresol.core.playback.AudiobookTimeline
import app.antresol.core.playback.Chapters
import app.antresol.core.playback.PartPosition
import app.antresol.mobile.settings.AppSettings
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.random.Random

/** The queue entry that is playing, as the UI shows it. */
data class NowPlaying(
    val ref: MediaRef,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val starred: Boolean,
    val albumId: Long,
    val hasLyrics: Boolean,
    /** Audiobooks: where this part starts on the whole-book timeline, and the book's length. */
    val partStartMs: Long,
    val bookDurationMs: Long,
) {
    val isAudiobook: Boolean get() = ref is MediaRef.BookPart
    val trackId: Long? get() = (ref as? MediaRef.Track)?.trackId
    val bookId: Long? get() = (ref as? MediaRef.BookPart)?.bookId
}

/** A running sleep timer: at a time, or when the current track/chapter ends. */
sealed interface SleepTimer {
    data class At(val endsAtMs: Long, val minutes: Int) : SleepTimer
    data object EndOfItem : SleepTimer
}

/**
 * The app's handle on [PlaybackService]: a Media3 [MediaController] plus Compose state for the
 * mini-player and the Now Playing screen, and the commands the UI needs (play an album, shuffle,
 * play an audiobook from a whole-book position, chapters, speed, sleep timer, star).
 * Lives in the Application so it survives activity restarts; it binds to the service on the first
 * command (the service only runs while this process does, so there is nothing to find earlier),
 * reconnects after the service died, and lets go of it when the queue is stopped.
 */
class PlaybackConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val client: () -> AntresolClient?,
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var pollJob: Job? = null
    private var sleepJob: Job? = null
    private val starOverrides = mutableStateMapOf<Long, Boolean>()

    /** Screens showing the position (mini player, Now Playing); the position is polled only for them. */
    private var positionWatchers = 0

    /** Changes with every new queue ([playTracks], [playAudiobook], [stop]); see [appendTracks]. */
    private var queueGeneration = 0

    /** Counts entry changes, including a repeated track (the "end of track" sleep timer). */
    private var transitions = 0

    var current: NowPlaying? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set

    /** Playing or about to (buffering); drives the play/pause icon. */
    var playWhenReady: Boolean by mutableStateOf(false)
        private set
    var isBuffering: Boolean by mutableStateOf(false)
        private set

    /** Position inside the current queue entry. */
    var positionMs: Long by mutableLongStateOf(0)
        private set
    var durationMs: Long by mutableLongStateOf(0)
        private set
    var shuffle: Boolean by mutableStateOf(false)
        private set
    var repeatMode: Int by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var speed: Float by mutableFloatStateOf(1f)
        private set
    var queue: List<MediaItem> by mutableStateOf(emptyList())
        private set
    var currentIndex: Int by mutableIntStateOf(0)
        private set

    /** The audiobook being played (chapters, title), or null for music. */
    var book: BookDetail? by mutableStateOf(null)
        private set
    var sleepTimer: SleepTimer? by mutableStateOf(null)
        private set

    /** The last playback error, cleared when playback recovers. */
    var error: PlaybackException? by mutableStateOf(null)
        private set

    /** Set when the media notification asked to show Now Playing; the shell consumes it. */
    var nowPlayingRequested: Boolean by mutableStateOf(false)

    // region Derived audiobook state

    /** Whole-book position for audiobooks, else the entry position. */
    val bookPositionMs: Long get() = (current?.partStartMs ?: 0L) + positionMs

    val bookDurationMs: Long get() = current?.bookDurationMs?.takeIf { it > 0 } ?: durationMs

    val currentChapter: Chapter? get() = book?.chapters?.let { it.getOrNull(Chapters.indexAt(it, bookPositionMs)) }

    // endregion

    /** Binds to the service (starting it if needed); safe to call repeatedly. */
    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) = handleDisconnected(controller)
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            val connected = try {
                future.get()
            } catch (e: Exception) {
                // Commands queued for this attempt must not run against a later connection.
                controllerFuture = null
                pending.clear()
                return@addListener
            }
            onConnected(connected)
        }, ContextCompat.getMainExecutor(context))
    }

    // region Commands

    /**
     * Plays [tracks] from [startIndex]; with [shuffled] the order is shuffled and the start is random.
     * Returns the queue's generation for [appendTracks], or null when nothing was queued.
     */
    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffled: Boolean = false): Int? {
        val urls = client()?.urls ?: return null
        if (tracks.isEmpty()) return null
        val items = tracks.map { QueueItems.track(it, urls) }
        val start = if (shuffled) Random.nextInt(items.size) else startIndex.coerceIn(0, items.lastIndex)
        book = null
        val generation = ++queueGeneration
        withController { c ->
            c.shuffleModeEnabled = shuffled
            c.setPlaybackSpeed(1f)
            c.setMediaItems(items, start, 0L)
            c.prepare()
            c.play()
        }
        return generation
    }

    /** Adds [tracks] to the end of the queue started by [playTracks], unless another queue replaced it. */
    fun appendTracks(generation: Int, tracks: List<Track>) {
        if (generation != queueGeneration || tracks.isEmpty()) return
        val urls = client()?.urls ?: return
        val items = tracks.map { QueueItems.track(it, urls) }
        withController { c -> if (generation == queueGeneration) c.addMediaItems(items) }
    }

    /** Plays [book] from [fromBookMs] on the whole-book timeline. */
    fun playAudiobook(book: BookDetail, fromBookMs: Long) {
        val urls = client()?.urls ?: return
        val items = QueueItems.audiobook(book, urls)
        if (items.isEmpty()) return
        val timeline = AudiobookTimeline.of(book.files, book.durationMs)
        // Unknown part lengths: whole-book positions cannot be located, start at the beginning.
        val position = if (timeline.isComplete) timeline.locate(fromBookMs) else PartPosition(0, 0)
        this.book = book
        queueGeneration++
        withController { c ->
            c.shuffleModeEnabled = false
            c.repeatMode = Player.REPEAT_MODE_OFF
            c.setMediaItems(items, position.partIndex, position.offsetMs)
            c.setPlaybackSpeed(settings.audiobookSpeed)
            c.prepare()
            c.play()
        }
    }

    /** "Resume": keeps going if [book] is already loaded, else starts at [fromBookMs]. */
    fun resumeAudiobook(book: BookDetail, fromBookMs: Long) {
        if (current?.bookId == book.card.id) {
            this.book = book
            withController { it.play() }
        } else {
            playAudiobook(book, fromBookMs)
        }
    }

    fun togglePlayPause() = withController { c ->
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
        if (c.playWhenReady) c.pause() else c.play()
    }

    /** Pauses if connected (a video is about to start). */
    fun pause() {
        controller?.pause()
    }

    fun next() = withController { it.seekToNextMediaItem() }

    /** Restarts the track after 3 s, else goes to the previous one (Media3's rule). */
    fun previous() = withController { it.seekToPrevious() }

    fun seekTo(ms: Long) = withController { it.seekTo(ms.coerceAtLeast(0)) }

    /** Seeks on the whole-book timeline (audiobook scrubber, chapters). */
    fun seekBook(bookMs: Long) {
        val playing = book ?: return seekTo(bookMs)
        val timeline = AudiobookTimeline.of(playing.files, playing.durationMs)
        // Unknown part lengths: only the current part's own timeline can be trusted.
        if (!timeline.isComplete) return seekTo(bookMs - (current?.partStartMs ?: 0L))
        val position = timeline.locate(bookMs)
        withController { it.seekTo(position.partIndex, position.offsetMs) }
    }

    /** Skips by [deltaMs] across part boundaries for audiobooks, within the track for music. */
    fun skipBy(deltaMs: Long) {
        if (current?.isAudiobook == true) {
            seekBook((bookPositionMs + deltaMs).coerceIn(0, bookDurationMs))
        } else {
            seekTo((positionMs + deltaMs).coerceIn(0, durationMs.coerceAtLeast(0)))
        }
    }

    fun playChapter(chapter: Chapter) {
        seekBook(chapter.startMs)
        withController { it.play() }
    }

    fun jumpTo(index: Int) = withController { c ->
        c.seekTo(index, 0L)
        c.play()
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    /** Off → all → one → off. */
    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun changeSpeed(value: Float) {
        if (current?.isAudiobook == true) settings.updateAudiobookSpeed(value)
        withController { it.setPlaybackSpeed(value) }
    }

    /** Stars or unstars the current track; shown at once, undone if the server refuses. */
    fun toggleStar() {
        val playing = current ?: return
        val trackId = playing.trackId ?: return
        val api = client() ?: return
        val starred = !playing.starred
        starOverrides[trackId] = starred
        current = playing.copy(starred = starred)
        scope.launch {
            try {
                if (starred) api.star(trackId) else api.unstar(trackId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                starOverrides[trackId] = !starred
                if (current?.trackId == trackId) current = current?.copy(starred = !starred)
            }
        }
    }

    /** Pauses after [minutes], or at the end of the current track/chapter when null. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        val timer = if (minutes != null) {
            SleepTimer.At(System.currentTimeMillis() + minutes * 60_000L, minutes)
        } else {
            SleepTimer.EndOfItem
        }
        sleepTimer = timer
        sleepJob = scope.launch {
            when (timer) {
                is SleepTimer.At -> delay(timer.endsAtMs - System.currentTimeMillis())
                SleepTimer.EndOfItem -> {
                    val start = itemKey()
                    while (isActive && itemKey() == start && current != null) delay(SLEEP_POLL_MS)
                }
            }
            controller?.pause()
            sleepTimer = null
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepTimer = null
    }

    /**
     * Stops playback, empties the queue and lets go of the service so it can end (profile switch,
     * closing the player).
     */
    fun stop() {
        val connected = controller
        resetQueueState()
        if (connected != null) {
            connected.stop()
            connected.clearMediaItems()
        }
        disconnect()
    }

    /**
     * Stops playback and waits (briefly) until the service has written the last audiobook position,
     * so it still goes out with the current token. Used before sign-out revokes that token.
     */
    suspend fun stopAndFlush() {
        val connected = controller
        resetQueueState()
        if (connected == null) return disconnect()
        val command = SessionCommand(PlaybackService.FLUSH_AND_STOP, Bundle.EMPTY)
        if (connected.isSessionCommandAvailable(command)) {
            val result = connected.sendCustomCommand(command, Bundle.EMPTY)
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    result.addListener({ cont.resume(Unit) }, ContextCompat.getMainExecutor(context))
                }
            }
        } else {
            connected.stop()
            connected.clearMediaItems()
        }
        disconnect()
    }

    /**
     * Registers a screen that shows the playback position; it is polled only while one is
     * registered and something plays. Call the returned function when the screen stops.
     */
    fun watchPosition(): () -> Unit {
        positionWatchers++
        controller?.let(::updatePollJob)
        var released = false
        return {
            if (!released) {
                released = true
                positionWatchers--
                controller?.let(::updatePollJob)
            }
        }
    }

    // endregion

    private fun resetQueueState() {
        cancelSleepTimer()
        book = null
        starOverrides.clear()
        pending.clear()
        queueGeneration++
    }

    /** Releases the controller (the service may stop) and clears what the UI shows. */
    private fun disconnect() {
        val connected = controller
        val future = controllerFuture
        controller = null
        controllerFuture = null
        if (connected != null) connected.release() else future?.let(MediaController::releaseFuture)
        resetUiState()
    }

    /** The service went away (it died or was stopped): the next command connects again. */
    private fun handleDisconnected(disconnected: MediaController) {
        if (controller !== disconnected) return
        controller = null
        controllerFuture = null
        cancelSleepTimer()
        book = null
        resetUiState()
    }

    private fun resetUiState() {
        pollJob?.cancel()
        pollJob = null
        current = null
        isPlaying = false
        playWhenReady = false
        isBuffering = false
        positionMs = 0
        durationMs = 0
        queue = emptyList()
        currentIndex = 0
    }

    /**
     * The current chapter (read from the controller, so it works with no screen polling), or the
     * entry, counting repeats of the same track.
     */
    private fun itemKey(): Any? {
        val c = controller ?: return null
        val chapters = book?.chapters.orEmpty()
        val playing = current
        if (playing?.isAudiobook == true && chapters.isNotEmpty()) {
            return "chapter-" + Chapters.indexAt(chapters, playing.partStartMs + c.currentPosition.coerceAtLeast(0))
        }
        return "item-$transitions"
    }

    private fun withController(block: (MediaController) -> Unit) {
        val connected = controller
        if (connected != null) {
            block(connected)
        } else {
            pending += block
            connect()
        }
    }

    private fun onConnected(connected: MediaController) {
        controller = connected
        connected.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitions++
            }

            override fun onPlayerError(error: PlaybackException) {
                this@PlaybackConnection.error = error
            }
        })
        val queued = pending.toList()
        pending.clear()
        queued.forEach { it(connected) }
        sync()
    }

    /** Polls the position only while something plays and a screen shows it; events update it otherwise. */
    private fun updatePollJob(c: MediaController) {
        val wanted = c.isPlaying && positionWatchers > 0
        if (!wanted) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive && controller === c) {
                updatePosition(c)
                delay(POLL_MS)
            }
        }
    }

    private fun sync() {
        val c = controller ?: return
        isPlaying = c.isPlaying
        playWhenReady = c.playWhenReady
        isBuffering = c.playbackState == Player.STATE_BUFFERING
        shuffle = c.shuffleModeEnabled
        repeatMode = c.repeatMode
        speed = c.playbackParameters.speed
        if (c.playbackState == Player.STATE_READY) error = null
        queue = List(c.mediaItemCount) { c.getMediaItemAt(it) }
        currentIndex = c.currentMediaItemIndex
        val item = c.currentMediaItem
        current = item?.toNowPlaying()
        if (current?.bookId != book?.card?.id) book = null
        updatePosition(c)
        updatePollJob(c)
    }

    private fun updatePosition(c: MediaController) {
        positionMs = c.currentPosition.coerceAtLeast(0)
        val duration = c.duration
        durationMs = if (duration != C.TIME_UNSET && duration > 0) {
            duration
        } else {
            c.currentMediaItem?.mediaMetadata?.extras?.getLong(QueueExtras.DURATION_MS) ?: 0L
        }
    }

    private fun MediaItem.toNowPlaying(): NowPlaying? {
        val ref = MediaRef.parse(mediaId) ?: return null
        val metadata = mediaMetadata
        val extras = metadata.extras
        val trackId = (ref as? MediaRef.Track)?.trackId
        return NowPlaying(
            ref = ref,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            artworkUrl = metadata.artworkUri?.toString(),
            starred = trackId?.let { starOverrides[it] } ?: extras?.getBoolean(QueueExtras.STARRED) ?: false,
            albumId = extras?.getLong(QueueExtras.ALBUM_ID) ?: 0L,
            hasLyrics = extras?.getBoolean(QueueExtras.HAS_LYRICS) ?: false,
            partStartMs = extras?.getLong(QueueExtras.PART_START_MS) ?: 0L,
            bookDurationMs = extras?.getLong(QueueExtras.BOOK_DURATION_MS) ?: 0L,
        )
    }

    companion object {
        /** Speeds offered for audiobooks (the web app's 0.75–3.0 in steps of 0.25). */
        val SPEEDS: List<Float> = (3..12).map { it * 0.25f }

        /** Sleep timer presets in minutes. */
        val SLEEP_MINUTES = listOf(5, 15, 30, 45, 60)

        private const val POLL_MS = 250L
        private const val SLEEP_POLL_MS = 300L
        private const val FLUSH_TIMEOUT_MS = 3_000L
    }
}

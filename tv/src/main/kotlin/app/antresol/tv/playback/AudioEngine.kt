package app.antresol.tv.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Chapter
import app.antresol.core.model.Track
import app.antresol.core.playback.AudiobookTimeline
import app.antresol.core.playback.Chapters
import app.antresol.core.playback.PlaybackReporter
import app.antresol.core.playback.PlaybackTiming
import app.antresol.core.playback.ScrobbleTracker
import app.antresol.core.playback.playbackApi
import app.antresol.ui.playback.TranscodeSeekingPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient

/** What the audio player holds: a music queue or one audiobook. */
sealed interface AudioContent {
    data class Music(val tracks: List<Track>) : AudioContent

    data class Audiobook(val book: BookDetail) : AudioContent {
        val timeline = AudiobookTimeline.of(book.files)
        val durationMs: Long get() = book.durationMs.takeIf { it > 0 } ?: timeline.totalMs
        val chapters: List<Chapter> get() = book.chapters
    }
}

/** Sleep timer choices. [EndOfItem] pauses at the end of the chapter (audiobook) or song (music). */
enum class SleepTimer(val minutes: Int) { Off(0), Min15(15), Min30(30), Min45(45), Min60(60), EndOfItem(0) }

/**
 * The app's audio player for music and audiobooks, shared by every screen and by
 * [PlaybackService] (which wraps [player] in a MediaSession so playback continues in the background
 * and shows on the system's now-playing surfaces).
 *
 * Streams use the signed-in client's OkHttp (Bearer header), so long queues never outlive a media key.
 * Music: scrobbles once per track after min(30 s, half the track). Audiobooks: the parts play as one
 * book; progress is the whole-book position, written every 10 s, on pause and on stop.
 * State is Compose snapshot state, updated on the main thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AudioEngine(
    private val context: Context,
    private val clientProvider: () -> AntresolClient?,
    private val scope: CoroutineScope,
) {
    private val fallbackHttp by lazy { OkHttpClient() }
    private val callFactory = Call.Factory { request -> (clientProvider()?.httpClient ?: fallbackHttp).newCall(request) }

    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(callFactory))))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(BACK_STEP_MS)
        .setSeekForwardIncrementMs(FORWARD_STEP_MS)
        .build()

    private val knownDurations = HashMap<String, Long>()

    /** The player to control and to hand to a MediaSession. Positions include transcode offsets. */
    val player: Player = TranscodeSeekingPlayer(exo, { knownDurations[it.mediaId] }, ::startingAt)

    var content: AudioContent? by mutableStateOf(null)
        private set

    /** Index of the current item in the media list (track of the queue, or audiobook part). */
    var index: Int by mutableIntStateOf(0)
        private set

    var isPlaying: Boolean by mutableStateOf(false)
        private set

    /** Playing or about to (buffering); false while paused. Drives the Play/Pause button. */
    var playWhenReady: Boolean by mutableStateOf(false)
        private set

    var isBuffering: Boolean by mutableStateOf(false)
        private set

    /** Position in the current track, or the whole-book position of an audiobook. */
    var positionMs: Long by mutableLongStateOf(0)
        private set

    /** Length of the current track, or of the whole audiobook. */
    var durationMs: Long by mutableLongStateOf(0)
        private set

    var shuffle: Boolean by mutableStateOf(false)
        private set

    var repeatMode: Int by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set

    /** Audiobook playback speed (music always plays at 1×). */
    var speed: Float by mutableFloatStateOf(1f)
        private set

    var sleepTimer: SleepTimer by mutableStateOf(SleepTimer.Off)
        private set

    /** Time left on a timed sleep timer. */
    var sleepRemainingMs: Long? by mutableStateOf(null)
        private set

    var error: PlaybackException? by mutableStateOf(null)
        private set

    val currentTrack: Track? get() = (content as? AudioContent.Music)?.tracks?.getOrNull(index)

    private var reporter: BookReporter? = null
    private var scrobble: ScrobbleTracker? = null
    private var scrobbledMediaId: String? = null
    private var queueId = 0L
    private var lastTickAt = 0L
    private var sleepEndsAt: Long? = null
    private var sleepUntilBookMs: Long? = null
    private var ticker: Job? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                lastTickAt = SystemClock.elapsedRealtime()
            }

            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
                playWhenReady = ready
                if (!ready) scope.launch { reporter?.reporter?.flush() }
                if (!ready && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) clearSleepTimer()
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error = null
                if (state == Player.STATE_ENDED) scope.launch { reporter?.reporter?.flush() }
                updatePosition()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                index = exo.currentMediaItemIndex
                // A transcoded seek replaces the item with the same track at `&t=`: keep the played time.
                val sameItem = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem?.mediaId == scrobbledMediaId
                if (!sameItem) startScrobble()
                updatePosition()
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                shuffle = enabled
            }

            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
            }

            override fun onPlayerError(e: PlaybackException) {
                error = e
            }
        })
    }

    // region Starting

    /**
     * Plays [tracks] from [startIndex]; [shuffled] plays them in a random order instead. Returns an id
     * of this queue for [appendTracks], or null when nothing started.
     */
    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffled: Boolean = false): Long? {
        val client = clientProvider() ?: return null
        if (tracks.isEmpty()) return null
        endAudiobook()
        val queue = if (shuffled) tracks.shuffled() else tracks
        val start = if (shuffled) 0 else startIndex.coerceIn(0, queue.lastIndex)
        knownDurations.clear()
        val items = queue.map { trackItem(client, it) }
        content = AudioContent.Music(queue)
        queueId++
        exo.shuffleModeEnabled = false
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.setPlaybackSpeed(1f)
        begin(items, start, 0)
        return queueId
    }

    /**
     * Adds [tracks] to the end of the music queue [queue] (from [playTracks]), e.g. the next pages of
     * the Songs list. Does nothing when something else has started playing since.
     */
    fun appendTracks(queue: Long, tracks: List<Track>) {
        val client = clientProvider() ?: return
        val music = content as? AudioContent.Music ?: return
        if (queue != queueId || tracks.isEmpty()) return
        exo.addMediaItems(tracks.map { trackItem(client, it) })
        content = AudioContent.Music(music.tracks + tracks)
    }

    private fun trackItem(client: AntresolClient, track: Track): MediaItem = mediaItem(
        id = "$TRACK_PREFIX${track.id}",
        uri = client.urls.track(track.id),
        durationMs = track.durationMs,
        metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(client.urls.image(track.cover)?.let(Uri::parse))
            .setTrackNumber(track.trackNo.takeIf { it > 0 })
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build(),
    )

    /** Plays [book] from the whole-book position [startBookMs] (use `Resume.audiobookStartMs`). */
    fun playAudiobook(book: BookDetail, startBookMs: Long) {
        val client = clientProvider() ?: return
        if (book.files.isEmpty()) return
        endAudiobook()
        val audiobook = AudioContent.Audiobook(book)
        val start = audiobook.timeline.locate(startBookMs.coerceIn(0, audiobook.durationMs))
        knownDurations.clear()
        val artwork = client.urls.image(book.card.poster)?.let(Uri::parse)
        val items = book.files.mapIndexed { part, file ->
            mediaItem(
                id = "$PART_PREFIX${file.id}:$part",
                uri = client.urls.audio(file.id),
                durationMs = file.durationMs,
                metadata = MediaMetadata.Builder()
                    .setTitle(book.card.title)
                    .setArtist(book.authors.joinToString(", "))
                    .setArtworkUri(artwork)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .build(),
            )
        }
        content = audiobook
        queueId++
        exo.shuffleModeEnabled = false
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.setPlaybackSpeed(speed)
        begin(items, start.partIndex, start.offsetMs)
        reporter = BookReporter(client, audiobook).also { it.reporter.start() }
    }

    private fun begin(items: List<MediaItem>, startIndex: Int, startMs: Long) {
        error = null
        clearSleepTimer()
        exo.setMediaItems(items, startIndex, startMs)
        (player as TranscodeSeekingPlayer).expectStart(startIndex, startMs)
        index = startIndex
        exo.prepare()
        exo.play()
        startScrobble()
        updatePosition()
        startTicker()
        // Creates the service, which adds the MediaSession and keeps playback alive in the background.
        context.startService(Intent(context, PlaybackService::class.java))
    }

    private fun mediaItem(id: String, uri: String, durationMs: Long, metadata: MediaMetadata): MediaItem {
        if (durationMs > 0) knownDurations[id] = durationMs
        return MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(metadata).build()
    }

    /** The same item streaming from [positionMs] (server-side `t`), for transcoded streams. */
    private fun startingAt(item: MediaItem, positionMs: Long): MediaItem {
        val urls = clientProvider()?.urls ?: return item
        val seconds = positionMs / 1000.0
        val uri = when {
            item.mediaId.startsWith(TRACK_PREFIX) -> urls.track(item.mediaId.removePrefix(TRACK_PREFIX).toLong(), startSec = seconds)
            item.mediaId.startsWith(PART_PREFIX) ->
                urls.audio(item.mediaId.removePrefix(PART_PREFIX).substringBefore(':').toLong(), startSec = seconds)
            else -> return item
        }
        return item.buildUpon().setUri(uri).build()
    }

    // endregion

    // region Control

    fun togglePlayPause() {
        if (exo.mediaItemCount == 0) return
        if (exo.playbackState == Player.STATE_ENDED) {
            player.seekTo(0, 0)
            exo.play()
            return
        }
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.playWhenReady = !exo.playWhenReady
    }

    fun pause() {
        exo.pause()
    }

    /** Seeks within the current track, or to a whole-book position of an audiobook. */
    fun seekTo(targetMs: Long) {
        val target = targetMs.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        when (val current = content) {
            is AudioContent.Audiobook -> {
                val position = current.timeline.locate(target)
                if (position.partIndex == exo.currentMediaItemIndex) player.seekTo(position.offsetMs) else player.seekTo(position.partIndex, position.offsetMs)
            }
            is AudioContent.Music -> player.seekTo(target)
            null -> return
        }
        positionMs = target
        sleepUntilBookMs?.let { if (target > it) armEndOfItem() }
    }

    fun skipBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    /** Next track, or the next chapter of an audiobook. */
    fun next() {
        when (val current = content) {
            is AudioContent.Audiobook -> Chapters.nextStartMs(current.chapters, positionMs)?.let(::seekTo)
            is AudioContent.Music -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            null -> Unit
        }
    }

    /** Restart, or the previous track/chapter when near the start. */
    fun previous() {
        when (val current = content) {
            is AudioContent.Audiobook -> seekTo(Chapters.previousStartMs(current.chapters, positionMs))
            is AudioContent.Music -> player.seekToPrevious()
            null -> Unit
        }
    }

    /** Jumps to the queue entry [queueIndex] (music). */
    fun playQueueItem(queueIndex: Int) {
        if (content !is AudioContent.Music || queueIndex !in 0 until exo.mediaItemCount) return
        player.seekTo(queueIndex, 0)
        exo.play()
    }

    fun toggleShuffle() {
        exo.shuffleModeEnabled = !exo.shuffleModeEnabled
    }

    /** Off → repeat all → repeat one → off. */
    fun cycleRepeat() {
        exo.repeatMode = when (exo.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setPlaybackSpeed(value: Float) {
        speed = value
        if (content is AudioContent.Audiobook) exo.setPlaybackSpeed(value)
    }

    fun startSleepTimer(option: SleepTimer) {
        clearSleepTimer()
        sleepTimer = option
        when (option) {
            SleepTimer.Off -> Unit
            SleepTimer.EndOfItem -> armEndOfItem()
            else -> {
                sleepEndsAt = SystemClock.elapsedRealtime() + option.minutes * 60_000L
                sleepRemainingMs = option.minutes * 60_000L
            }
        }
    }

    private fun armEndOfItem() {
        when (val current = content) {
            is AudioContent.Audiobook -> sleepUntilBookMs = Chapters.endMs(current.chapters, positionMs, current.durationMs)
            is AudioContent.Music -> exo.pauseAtEndOfMediaItems = true
            null -> Unit
        }
    }

    private fun clearSleepTimer() {
        sleepTimer = SleepTimer.Off
        sleepEndsAt = null
        sleepUntilBookMs = null
        sleepRemainingMs = null
        exo.pauseAtEndOfMediaItems = false
    }

    /** Replaces a track of the queue, e.g. after starring it. */
    fun updateTrack(track: Track) {
        val music = content as? AudioContent.Music ?: return
        if (music.tracks.none { it.id == track.id }) return
        content = AudioContent.Music(music.tracks.map { if (it.id == track.id) track else it })
    }

    /** Retries after a playback error at the same spot. */
    fun retry() {
        error = null
        exo.prepare()
        exo.play()
    }

    /** Stops playback and forgets the queue; the audiobook's final position is saved. Used on sign-out and profile switch. */
    fun stop() {
        stopPlayback()
    }

    /** [stop], returning once the audiobook's final position is saved (before a sign-out revokes the token). */
    suspend fun stopAndFlush() {
        stopPlayback()?.join()
    }

    private fun stopPlayback(): Job? {
        if (content == null) return null
        val finalWrite = endAudiobook()
        clearSleepTimer()
        exo.stop()
        exo.clearMediaItems()
        content = null
        ticker?.cancel()
        ticker = null
        positionMs = 0
        durationMs = 0
        scrobbledMediaId = null
        queueId++
        context.stopService(Intent(context, PlaybackService::class.java))
        return finalWrite
    }

    /** The next entries of the music queue in play order (shuffle aware), with their media indexes. */
    fun upNext(limit: Int = 20): List<Pair<Int, Track>> {
        val tracks = (content as? AudioContent.Music)?.tracks ?: return emptyList()
        val timeline = exo.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val result = ArrayList<Pair<Int, Track>>()
        var i = timeline.getNextWindowIndex(exo.currentMediaItemIndex, Player.REPEAT_MODE_OFF, exo.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && result.size < limit) {
            tracks.getOrNull(i)?.let { result += i to it }
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, exo.shuffleModeEnabled)
        }
        return result
    }

    // endregion

    private fun endAudiobook(): Job? {
        val ending = reporter ?: return null
        reporter = null
        // Freeze the position now: the final write runs after the queue is replaced or cleared.
        ending.frozenPositionMs = bookPositionMs()
        return scope.launch {
            try {
                ending.reporter.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort; the periodic writes already saved a recent position.
            }
        }
    }

    private fun bookPositionMs(): Long {
        val audiobook = content as? AudioContent.Audiobook ?: return 0
        if (exo.mediaItemCount == 0) return 0
        val part = exo.currentMediaItemIndex.coerceIn(0, audiobook.timeline.partCount - 1)
        return audiobook.timeline.bookMs(part, player.currentPosition)
    }

    /** Progress reporting of one audiobook; [frozenPositionMs] pins the final write once it is replaced. */
    private inner class BookReporter(client: AntresolClient, audiobook: AudioContent.Audiobook) {
        var frozenPositionMs: Long? = null
        val reporter = PlaybackReporter(
            api = client.playbackApi(),
            scope = scope,
            itemId = audiobook.book.card.id,
            durationMs = audiobook.durationMs,
            positionMs = { frozenPositionMs ?: bookPositionMs() },
            isPlaying = { exo.isPlaying },
            timing = PlaybackTiming.AUDIOBOOK,
        )
    }

    private fun startScrobble() {
        scrobbledMediaId = exo.currentMediaItem?.mediaId
        scrobble = currentTrack?.let { ScrobbleTracker(it.durationMs) }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        lastTickAt = SystemClock.elapsedRealtime()
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val played = if (exo.isPlaying) now - lastTickAt else 0L
        lastTickAt = now
        updatePosition()
        val track = currentTrack
        if (track != null && scrobble?.onPlayed(played) == true) {
            scope.launch { runCatching { clientProvider()?.recordPlay(track.id) } }
        }
        sleepEndsAt?.let { endsAt ->
            sleepRemainingMs = (endsAt - now).coerceAtLeast(0)
            if (now >= endsAt) {
                exo.pause()
                clearSleepTimer()
            }
        }
        sleepUntilBookMs?.let { until ->
            if (positionMs >= until - SLEEP_CHAPTER_GUARD_MS) {
                exo.pause()
                clearSleepTimer()
            }
        }
    }

    private fun updatePosition() {
        when (val current = content) {
            is AudioContent.Audiobook -> {
                positionMs = bookPositionMs()
                durationMs = current.durationMs
            }
            is AudioContent.Music -> {
                positionMs = player.currentPosition.coerceAtLeast(0)
                val duration = player.duration
                durationMs = if (duration == C.TIME_UNSET) currentTrack?.durationMs ?: 0 else duration
            }
            null -> Unit
        }
    }

    companion object {
        const val BACK_STEP_MS = 15_000L
        const val FORWARD_STEP_MS = 30_000L

        /** Speeds offered for audiobooks, as on the web app (0.75–3×). */
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

        private const val TRACK_PREFIX = "track:"
        private const val PART_PREFIX = "part:"
        private const val TICK_MS = 500L
        private const val SLEEP_CHAPTER_GUARD_MS = 300L
    }
}

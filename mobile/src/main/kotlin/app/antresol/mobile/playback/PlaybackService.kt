package app.antresol.mobile.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.antresol.core.playback.PlaybackReporter
import app.antresol.core.playback.PlaybackTiming
import app.antresol.core.playback.ScrobbleTracker
import app.antresol.core.playback.playbackApi
import app.antresol.mobile.AntresolMobileApp
import app.antresol.mobile.MainActivity
import app.antresol.mobile.settings.AppSettings
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Background playback of music and audiobooks: one ExoPlayer behind a Media3 [MediaSession]
 * (system media notification, lock screen, headset buttons, Bluetooth). Streams go through the
 * signed-in client's OkHttp (Bearer header). Besides playing, the service does what the spec asks
 * of a music/audiobook client: a play record once per track after min(30 s, half) of real
 * listening, and audiobook progress on the whole-book timeline every 10 s and on pause.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private val scope = MainScope()
    private val app get() = application as AntresolMobileApp
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null

    private var scrobbleTrackId: Long? = null
    private var scrobble: ScrobbleTracker? = null
    private var lastTickAt: Long? = null

    private var reporter: PlaybackReporter? = null
    private var reporterBookId: Long? = null

    /** The last whole-book position seen for [reporterBookId], for the final write after the queue moved on. */
    private var lastBookMs = 0L

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onCurrentItem(mediaItem)

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                captureBookPosition()
                lastTickAt = null
                reporter?.let { scope.launch { it.flush() } }
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (timeline.isEmpty) onCurrentItem(null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val fallbackHttp by lazy { OkHttpClient() }
        // Resolved per stream so a profile switch (new token) applies to the next item.
        val http = DataSource.Factory {
            OkHttpDataSource.Factory(app.session.client?.httpClient ?: fallbackHttp).createDataSource()
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        player.addListener(listener)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_SHOW_NOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .setBitmapLoader(CoilBitmapLoader(this, scope))
            .build()
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    override fun attachBaseContext(base: android.content.Context) {
        // Notification action labels follow the app's language setting.
        super.attachBaseContext(AppSettings(base).wrap(base))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps playing music; a paused or empty player ends the service.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        endReporter()
        mediaSession?.release()
        mediaSession = null
        player.removeListener(listener)
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun onCurrentItem(item: MediaItem?) {
        val ref = MediaRef.parse(item?.mediaId)
        val trackId = (ref as? MediaRef.Track)?.trackId
        if (trackId != scrobbleTrackId) {
            scrobbleTrackId = trackId
            scrobble = trackId?.let { ScrobbleTracker(item?.mediaMetadata?.extras?.getLong(QueueExtras.DURATION_MS) ?: 0L) }
        }
        val bookId = (ref as? MediaRef.BookPart)?.bookId
        if (bookId != reporterBookId) {
            endReporter()
            if (bookId != null && item != null) startReporter(bookId, item)
        }
    }

    private fun startReporter(bookId: Long, item: MediaItem) {
        val client = app.session.client ?: return
        val duration = item.mediaMetadata.extras?.getLong(QueueExtras.BOOK_DURATION_MS) ?: 0L
        reporterBookId = bookId
        lastBookMs = currentBookMs() ?: 0L
        reporter = PlaybackReporter(
            api = client.playbackApi(),
            scope = scope,
            itemId = bookId,
            durationMs = duration,
            positionMs = { currentBookMs()?.takeIf { currentBookId() == bookId } ?: lastBookMs },
            isPlaying = { player.isPlaying },
            timing = PlaybackTiming.AUDIOBOOK,
        ).also { it.start() }
    }

    /** Final write for the book that stopped being current; runs in the app scope to outlive the service. */
    private fun endReporter() {
        val ending = reporter ?: return
        reporter = null
        reporterBookId = null
        app.appScope.launch {
            try {
                ending.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Progress writes are best effort.
            }
        }
    }

    private fun tick() {
        captureBookPosition()
        if (!player.isPlaying) {
            lastTickAt = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        val played = lastTickAt?.let { now - it } ?: 0L
        lastTickAt = now
        val trackId = scrobbleTrackId ?: return
        if (scrobble?.onPlayed(played) == true) {
            val client = app.session.client ?: return
            scope.launch {
                try {
                    client.recordPlay(trackId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A missed play record only affects "Recently Played" and play counts.
                }
            }
        }
    }

    private fun captureBookPosition() {
        if (reporterBookId != null && currentBookId() == reporterBookId) currentBookMs()?.let { lastBookMs = it }
    }

    private fun currentBookId(): Long? = (MediaRef.parse(player.currentMediaItem?.mediaId) as? MediaRef.BookPart)?.bookId

    private fun currentBookMs(): Long? {
        val start = player.currentMediaItem?.mediaMetadata?.extras?.getLong(QueueExtras.PART_START_MS) ?: return null
        return start + player.currentPosition.coerceAtLeast(0)
    }

    private inner class SessionCallback : MediaSession.Callback {
        /** Controllers send entries without URIs; rebuild them from the media id. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val urls = app.session.client?.urls
                ?: return Futures.immediateFailedFuture(IllegalStateException("Not signed in"))
            return Futures.immediateFuture(mediaItems.mapNotNull { QueueItems.withUri(it, urls) }.toMutableList())
        }
    }

    companion object {
        const val SESSION_ID = "antresol-audio"
        const val SEEK_BACK_MS = 15_000L
        const val SEEK_FORWARD_MS = 30_000L
        private const val TICK_MS = 500L
    }
}

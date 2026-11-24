package app.antresol.tv.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.antresol.tv.AntresolTvApp
import app.antresol.tv.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Keeps music and audiobooks playing while the user browses or leaves the app, and publishes them
 * as a MediaSession (system now-playing card, remote and Assistant transport controls).
 * The player belongs to the app's [AudioEngine]; this service only wraps it.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = (application as AntresolTvApp).audio
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_NOW_PLAYING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, engine.player)
            .setBitmapLoader(CacheBitmapLoader(CoilBitmapLoader(this)))
            .setSessionActivity(openApp)
            .setCallback(Gate())
            .build()
            .also(::addSession)
    }

    /**
     * The service is exported for the system (the now-playing card, Assistant, Bluetooth), so who may
     * connect and what they may queue is limited: only the system's controllers and this app, and
     * only items rebuilt from their media id, never a URI of the controller's own.
     */
    private inner class Gate : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (controller.isTrusted || controller.packageName == packageName) {
                super.onConnect(session, controller)
            } else {
                MediaSession.ConnectionResult.reject()
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val urls = (application as AntresolTvApp).session.client?.urls
                ?: return Futures.immediateFailedFuture(IllegalStateException("Not signed in"))
            return Futures.immediateFuture(mediaItems.mapNotNull { AudioEngine.withUri(it, urls) }.toMutableList())
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}

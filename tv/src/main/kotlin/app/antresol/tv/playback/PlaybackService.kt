package app.antresol.tv.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommands
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
     * The service is exported for the system (the now-playing card, Assistant, Bluetooth), so what a
     * controller may do depends on who it is: the system's controllers and this app get everything,
     * any other app can still use the transport (play, pause, seek, next) but gets none of the
     * session's other commands and cannot queue items. Items that are added get their stream URL
     * built from the track or part id; anything else is dropped.
     */
    private inner class Gate : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (isPrivilegedController(controller.isTrusted, controller.uid, Process.myUid())) {
                super.onConnect(session, controller)
            } else {
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(SessionCommands.EMPTY)
                    .setAvailablePlayerCommands(GUEST_PLAYER_COMMANDS)
                    .build()
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

/**
 * A controller the system vouches for ([trusted]) or one of this app ([uid] is [ownUid]) may do everything. The
 * package name tells nothing: a media3 controller writes it into its own connection request, only the uid is the caller's.
 */
internal fun isPrivilegedController(trusted: Boolean, uid: Int, ownUid: Int): Boolean = trusted || uid == ownUid

/** What any other app's controller may do: the player's commands except queueing items. */
internal val GUEST_PLAYER_COMMANDS: Player.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
    .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
    .build()

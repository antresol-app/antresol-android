package app.antresol.ui.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import java.util.concurrent.atomic.AtomicInteger

/**
 * The media session of the live player (headset and Bluetooth buttons, the system's media
 * controls, picture-in-picture's play/pause). The stream is live: controllers get no seeking and
 * no skipping, so the system shows no scrubber; the title is the programme and the artist the
 * channel (see [LivePlayerViewModel]). Release it before the player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object LiveMediaSession {
    private val counter = AtomicInteger()

    /** Commands a live channel never offers. */
    private val LIVE_EXCLUDED = intArrayOf(
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SET_SPEED_AND_PITCH,
        Player.COMMAND_SET_REPEAT_MODE,
        Player.COMMAND_SET_SHUFFLE_MODE,
        Player.COMMAND_CHANGE_MEDIA_ITEMS,
        Player.COMMAND_SET_MEDIA_ITEM,
        Player.COMMAND_SET_PLAYLIST_METADATA,
    )

    /** Commands a controller of a live channel may send. */
    val PLAYER_COMMANDS: Player.Commands = Player.Commands.Builder().addAllCommands().removeAll(*LIVE_EXCLUDED).build()

    fun build(context: Context, player: Player): MediaSession =
        MediaSession.Builder(context, LivePlayer(player))
            // A unique id: the previous player's session may not be released yet.
            .setId("antresol-live-${counter.incrementAndGet()}")
            .setCallback(
                object : MediaSession.Callback {
                    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailablePlayerCommands(PLAYER_COMMANDS)
                            .build()
                },
            )
            .build()

    /**
     * The player as the system sees it: no seeking, and no duration (the live window's length
     * would draw a scrubber). The platform session takes its actions from here.
     */
    private class LivePlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().removeAll(*LIVE_EXCLUDED).build()

        override fun isCommandAvailable(command: Int): Boolean = command !in LIVE_EXCLUDED && super.isCommandAvailable(command)

        override fun getDuration(): Long = C.TIME_UNSET

        override fun getContentDuration(): Long = C.TIME_UNSET
    }
}

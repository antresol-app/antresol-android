package app.antresol.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player

/**
 * Whether the video view of [player] keeps the screen on: only while it plays or buffers to play.
 * A paused, ended or failed video lets the phone's display sleep and the TV's screensaver start.
 */
@Composable
fun rememberKeepScreenOn(player: Player): Boolean {
    var keepOn by remember(player) { mutableStateOf(keepsScreenOn(player.playWhenReady, player.playbackState)) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                keepOn = keepsScreenOn(player.playWhenReady, player.playbackState)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return keepOn
}

internal fun keepsScreenOn(playWhenReady: Boolean, playbackState: Int): Boolean =
    playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

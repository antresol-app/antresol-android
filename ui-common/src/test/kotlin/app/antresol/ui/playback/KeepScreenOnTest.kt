package app.antresol.ui.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen stays on only while a video plays or buffers to play. */
class KeepScreenOnTest {
    @Test
    fun onWhilePlayingOrBufferingToPlay() {
        assertTrue(keepsScreenOn(playWhenReady = true, playbackState = Player.STATE_READY))
        assertTrue(keepsScreenOn(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
    }

    @Test
    fun offWhilePausedEndedOrIdle() {
        assertFalse(keepsScreenOn(playWhenReady = false, playbackState = Player.STATE_READY))
        assertFalse(keepsScreenOn(playWhenReady = false, playbackState = Player.STATE_BUFFERING))
        assertFalse(keepsScreenOn(playWhenReady = true, playbackState = Player.STATE_ENDED))
        assertFalse(keepsScreenOn(playWhenReady = true, playbackState = Player.STATE_IDLE))
    }
}

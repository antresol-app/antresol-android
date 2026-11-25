package app.antresol.ui.playback

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A paused video whose surface goes and comes back (a screensaver over the player) gets a new
 * decoder on the new surface, never the old decoder moved onto it.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoRenderersTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.forAllSupportedMimeTypes()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aNewSurfaceStartsANewDecoder() {
        val run = pausedThenNewSurface(VideoRenderersFactory(context), surfaceGoneBetween = false)
        assertEquals(2, run.decoderStarts)
        assertTrue(run.played)
    }

    @Test
    fun aSurfaceThatGoesAndComesBackStartsANewDecoder() {
        // No placeholder surface while there is none: the decoder stops and starts on the new one.
        val run = pausedThenNewSurface(VideoRenderersFactory(context), surfaceGoneBetween = true)
        assertEquals(2, run.decoderStarts)
        assertTrue(run.played)
    }

    @Test
    fun exoPlayersOwnRenderersMoveTheDecoderInstead() {
        // The behaviour the TV chips break: one decoder, moved onto the new surface.
        val run = pausedThenNewSurface(DefaultRenderersFactory(context), surfaceGoneBetween = false)
        assertEquals(1, run.decoderStarts)
    }

    @Test
    fun everyDecoderTakesTheNewDecoderPath() {
        val renderers = VideoRenderersFactory(context).createRenderers(
            Handler(Looper.getMainLooper()),
            object : VideoRendererEventListener {},
            object : AudioRendererEventListener {},
            { },
            { },
        )
        val renderer = renderers.filterIsInstance<MediaCodecVideoRenderer>().single() as FreshCodecVideoRenderer
        assertTrue(renderer.codecNeedsSetOutputSurfaceWorkaround("c2.vendor.avc.decoder"))
        assertTrue(renderer.codecNeedsSetOutputSurfaceWorkaround("OMX.google.h264.decoder"))
    }

    private companion object {
        /** The apps' test clip (H.264 + AAC, 30 s); the tests run in the module's directory. */
        val CLIP = File("../test-fixtures/resources/trailer-sample.mp4")
    }

    private class Run(val decoderStarts: Int, val played: Boolean)

    private fun pausedThenNewSurface(renderers: RenderersFactory, surfaceGoneBetween: Boolean): Run {
        val player = ExoPlayer.Builder(context, renderers).setClock(FakeClock(true)).build()
        var decoderStarts = 0
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                decoderStarts++
            }
        })
        try {
            player.setVideoSurface(Surface(SurfaceTexture(1)))
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(CLIP)))
            player.prepare()
            run(player).untilState(Player.STATE_READY)
            if (surfaceGoneBetween) {
                player.setVideoSurface(null)
                run(player).untilPendingCommandsAreFullyHandled()
            }
            player.setVideoSurface(Surface(SurfaceTexture(2)))
            run(player).untilPendingCommandsAreFullyHandled()
            player.play()
            run(player).untilState(Player.STATE_ENDED)
            return Run(decoderStarts, played = player.currentPosition > 0)
        } finally {
            player.release()
        }
    }
}

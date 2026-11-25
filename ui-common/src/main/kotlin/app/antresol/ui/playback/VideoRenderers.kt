package app.antresol.ui.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The renderers of the video players: ExoPlayer's, with [FreshCodecVideoRenderer] for the picture.
 */
@UnstableApi
internal class VideoRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            FreshCodecVideoRenderer(
                context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback,
                eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            ),
        )
    }
}

/**
 * A video renderer that gives the decoder a new surface only by starting a new decoder on it.
 *
 * The picture's surface goes away whenever the player's window does (a screensaver, Home, another
 * app) and a new one comes back. ExoPlayer moves the running decoder onto the new surface
 * (`MediaCodec.setOutputSurface`, via a placeholder surface meanwhile), which some TV chips do
 * without an error and without a picture: sound and subtitles play on, the video stays black.
 * ExoPlayer starts a new decoder instead only on the devices it knows; here it always does. It
 * costs one decoder start when the window comes back, while the video is paused anyway.
 */
@UnstableApi
internal class FreshCodecVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
    context, codecAdapterFactory, mediaCodecSelector, allowedJoiningTimeMs, enableDecoderFallback,
    eventHandler, eventListener, maxDroppedFramesToNotify,
) {
    /** Every decoder: a new surface means a new decoder (and no placeholder surface between). */
    public override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean = true

    /** No decoder runs without a surface either (Android 15's detached mode): it starts on the next one. */
    public override fun shouldUseDetachedSurface(codecInfo: MediaCodecInfo): Boolean = false
}

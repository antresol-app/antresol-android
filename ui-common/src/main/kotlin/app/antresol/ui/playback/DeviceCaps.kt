package app.antresol.ui.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import app.antresol.core.model.PlaybackCaps
import app.antresol.core.playback.CodecSupport

/**
 * The `/api/play` caps of this device for ExoPlayer: decoders from [MediaCodecList] (HEVC, VP9, AV1,
 * AC-3/E-AC-3), Dolby passthrough ExoPlayer's audio sink can use on the current output, and the
 * tallest video the display and the hardware decoders handle (a 1080p stick gets 4K transcoded).
 */
@OptIn(UnstableApi::class)
object DeviceCaps {
    @Volatile
    private var decoderMimes: Set<String>? = null

    @Volatile
    private var decoderMaxHeight: Int? = null

    /**
     * Enumerates the codecs on first use (slow on low-end sticks): call off the main thread.
     * [limitToDisplay]: also cap the height at the display's (TVs, where a bigger video is wasted).
     * Phones and tablets scale any decodable video to the surface, so only the decoder limits them
     * and the user's quality setting decides the rest.
     */
    fun current(context: Context, limitToDisplay: Boolean = true): PlaybackCaps =
        CodecSupport.caps(decoders(), passthrough(context), maxHeight(context, limitToDisplay))

    /**
     * The tallest H.264/HEVC hardware decode and, with [limitToDisplay], at most the display's tallest
     * mode (its short side). Unknown or implausible values (below 720) mean no limit.
     */
    private fun maxHeight(context: Context, limitToDisplay: Boolean): Int? {
        val display = if (limitToDisplay) context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) else null
        val displayHeight = display?.supportedModes
            ?.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) }
            ?.takeIf { it >= MIN_PLAUSIBLE_HEIGHT }
        val decoderHeight = decoderHeight()?.takeIf { it >= MIN_PLAUSIBLE_HEIGHT }
        return listOfNotNull(displayHeight, decoderHeight).minOrNull()
    }

    /**
     * The tallest standard size (2160 or 1080 lines, landscape) a hardware H.264/HEVC decoder
     * accepts. `supportedHeights` is not used: it counts portrait sizes (1920 for a 1080p decoder).
     * Computed once; [NO_LIMIT] caches "unknown".
     */
    private fun decoderHeight(): Int? {
        val cached = decoderMaxHeight ?: runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.isHardware() }
                .flatMap { info ->
                    info.supportedTypes
                        .filter { it.lowercase() in SIZE_LIMITED_MIMES }
                        .mapNotNull { type ->
                            val video = info.getCapabilitiesForType(type).videoCapabilities ?: return@mapNotNull null
                            STANDARD_SIZES.firstOrNull { (w, h) -> video.isSizeSupported(w, h) }?.second
                        }
                }
                .maxOrNull()
        }.getOrNull().let { it ?: NO_LIMIT }.also { decoderMaxHeight = it }
        return cached.takeIf { it != NO_LIMIT }
    }

    /**
     * Decoder MIME types. Video counts only hardware decoders: a software VP9/AV1/HEVC decoder
     * stutters on TV-class CPUs, and the server transcodes to H.264 instead.
     */
    private fun decoders(): Set<String> = decoderMimes ?: runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .flatMapTo(HashSet()) { info ->
                info.supportedTypes.map { it.lowercase() }.filter { !it.startsWith("video/") || info.isHardware() }
            }
    }.getOrDefault(emptySet()).also { decoderMimes = it }

    private fun MediaCodecInfo.isHardware(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            val lower = name.lowercase()
            !lower.startsWith("omx.google.") && !lower.startsWith("c2.android.") && !lower.contains(".sw.")
        }

    /**
     * Re-read on every call: the audio output can change (a receiver switched on). Uses the same
     * source as ExoPlayer's audio sink, so the caps claim exactly what the renderer will bitstream.
     */
    private fun passthrough(context: Context): Set<String> {
        val capabilities = runCatching { AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null) }
            .getOrNull() ?: return emptySet()
        return buildSet {
            if (capabilities.supportsEncoding(C.ENCODING_AC3)) add("ac3")
            if (capabilities.supportsEncoding(C.ENCODING_E_AC3)) add("eac3")
        }
    }

    private const val MIN_PLAUSIBLE_HEIGHT = 720
    private const val NO_LIMIT = -1
    private val SIZE_LIMITED_MIMES = setOf("video/avc", "video/hevc")
    private val STANDARD_SIZES = listOf(3840 to 2160, 1920 to 1080, 1280 to 720)
}

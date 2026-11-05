package app.antresol.ui.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import app.antresol.core.model.PlaybackCaps
import app.antresol.core.playback.CodecSupport

/**
 * The `/api/play` caps of this device for ExoPlayer: decoders from [MediaCodecList] (HEVC, VP9, AV1,
 * AC-3/E-AC-3), Dolby passthrough reported by the connected audio outputs (HDMI, optical), and the
 * tallest video the display and the hardware decoders handle (a 1080p stick gets 4K transcoded).
 */
object DeviceCaps {
    @Volatile
    private var decoderMimes: Set<String>? = null

    @Volatile
    private var decoderMaxHeight: Int? = null

    /**
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

    private fun decoderHeight(): Int? = decoderMaxHeight ?: runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.isHardware() }
            .flatMap { info ->
                info.supportedTypes
                    .filter { it.lowercase() in SIZE_LIMITED_MIMES }
                    .mapNotNull { type -> info.getCapabilitiesForType(type).videoCapabilities?.supportedHeights?.upper }
            }
            .maxOrNull()
    }.getOrNull().also { decoderMaxHeight = it }

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

    /** Re-read on every call: the audio output can change (a receiver switched on). */
    private fun passthrough(context: Context): Set<String> {
        val audio = context.getSystemService(AudioManager::class.java) ?: return emptySet()
        val encodings = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in PASSTHROUGH_OUTPUTS }
            .flatMapTo(HashSet()) { it.encodings.toList() }
        return buildSet {
            if (AudioFormat.ENCODING_AC3 in encodings) add("ac3")
            if (AudioFormat.ENCODING_E_AC3 in encodings) add("eac3")
        }
    }

    private const val MIN_PLAUSIBLE_HEIGHT = 720
    private val SIZE_LIMITED_MIMES = setOf("video/avc", "video/hevc")

    private val PASSTHROUGH_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
    )
}

package app.antresol.ui.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecList
import app.antresol.core.model.PlaybackCaps
import app.antresol.core.playback.CodecSupport

/**
 * The `/api/play` caps of this device for ExoPlayer: decoders from [MediaCodecList] (HEVC, VP9, AV1,
 * AC-3/E-AC-3) plus Dolby passthrough reported by the connected audio outputs (HDMI, optical).
 */
object DeviceCaps {
    @Volatile
    private var decoderMimes: Set<String>? = null

    fun current(context: Context): PlaybackCaps = CodecSupport.caps(decoders(), passthrough(context))

    private fun decoders(): Set<String> = decoderMimes ?: runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .flatMapTo(HashSet()) { info -> info.supportedTypes.map { it.lowercase() } }
    }.getOrDefault(emptySet()).also { decoderMimes = it }

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

    private val PASSTHROUGH_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
    )
}

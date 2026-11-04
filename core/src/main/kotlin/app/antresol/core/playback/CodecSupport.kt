package app.antresol.core.playback

import app.antresol.core.model.PlaybackCaps

/**
 * Builds `/api/play` caps for ExoPlayer from what the device decodes. Apps pass the MIME types of
 * the decoders found in `MediaCodecList` plus the encodings the audio output passes through.
 */
object CodecSupport {
    /** API video codec name → decoder MIME type. H.264 is always present on Android. */
    val VIDEO_MIMES: Map<String, String> = linkedMapOf(
        "h264" to "video/avc",
        "hevc" to "video/hevc",
        "vp9" to "video/x-vnd.on2.vp9",
        "av1" to "video/av01",
    )

    /** Audio codecs ExoPlayer decodes on every device (platform decoders since API 21). */
    val ALWAYS_AUDIO: List<String> = listOf("aac", "mp3", "opus", "flac")

    /** Dolby codecs: need a decoder or HDMI/optical passthrough. */
    val OPTIONAL_AUDIO_MIMES: Map<String, String> = linkedMapOf(
        "ac3" to "audio/ac3",
        "eac3" to "audio/eac3",
    )

    val CONTAINERS: List<String> = listOf("mp4", "mkv", "webm")

    /**
     * [decoderMimes]: lower-case MIME types with a decoder. [passthrough]: API codec names
     * (`ac3`, `eac3`) the current audio output accepts as a bitstream. [maxHeight]: the tallest video
     * the device can show and decode (null = no limit); the server transcodes larger sources.
     */
    fun caps(decoderMimes: Set<String>, passthrough: Set<String> = emptySet(), maxHeight: Int? = null): PlaybackCaps {
        val video = VIDEO_MIMES.filter { (name, mime) -> name == "h264" || mime in decoderMimes }.keys.toList()
        val optionalAudio = OPTIONAL_AUDIO_MIMES.filter { (name, mime) -> mime in decoderMimes || name in passthrough }.keys
        return PlaybackCaps(
            videoCodecs = video,
            audioCodecs = ALWAYS_AUDIO + optionalAudio,
            containers = CONTAINERS,
            maxHeight = maxHeight,
        )
    }
}

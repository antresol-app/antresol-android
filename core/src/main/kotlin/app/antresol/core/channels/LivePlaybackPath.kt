package app.antresol.core.channels

import app.antresol.core.model.StreamSource
import java.net.URI

/**
 * How the live stream on screen reaches the device, for the banner's quiet line: «Напрямую ·
 * provider.example» (the provider's URL played as given; its host only, never the rest, which may
 * carry credentials) or «Через Antresol» (our channel, or the server's hidden-address redirect),
 * then the format, the video and audio codecs where known, and «Источник 2 из 3» when the channel
 * has fallbacks. The route and «Источник» are the app's (localised) words; [technical] is the rest.
 */
data class LivePlaybackPath(
    /** The provider's host for a direct stream; null: through Antresol. */
    val directHost: String?,
    val format: String? = null,
    val video: String? = null,
    val audio: String? = null,
    /** The source tried (1-based) and how many there are; null for our channels. */
    val attempt: Int? = null,
    val count: Int? = null,
) {
    val direct: Boolean get() = directHost != null

    /** The format, then «video / audio». */
    val technical: List<String>
        get() = buildList {
            format?.let(::add)
            listOfNotNull(video, audio).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" / ")) }
        }

    val showsAttempt: Boolean get() = attempt != null && (count ?: 0) > 1

    companion object {
        /** Our channel: the server's HLS. */
        fun own(video: String? = null, audio: String? = null) = LivePlaybackPath(null, "HLS", video, audio)

        /**
         * An external stream: [url] is the source's own (a path for the server's redirect), [index]
         * its place (0-based) among [count] sources.
         */
        fun external(url: String, format: String?, index: Int, count: Int, video: String? = null, audio: String? = null): LivePlaybackPath {
            val host = if (url.startsWith("/")) null else runCatching { URI(url).host }.getOrNull().orEmpty()
            return LivePlaybackPath(host, formatName(format, url), video, audio, index + 1, count)
        }

        /** The server's word for the format, else the URL's extension; null when unknown. */
        fun formatName(format: String?, url: String): String? {
            when (format?.lowercase()) {
                "hls" -> return "HLS"
                "ts" -> return "MPEG-TS"
            }
            val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
            return when {
                path.endsWith(".m3u8") || path.endsWith(".m3u") -> "HLS"
                path.endsWith(".ts") -> "MPEG-TS"
                else -> null
            }
        }
    }
}

/** Codec names for people, from a track's sample MIME type (ExoPlayer's `Format.sampleMimeType`). */
object MediaCodecs {
    fun name(mime: String?): String? = when (mime?.lowercase()) {
        null -> null
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/dolby-vision" -> "Dolby Vision"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/mpeg2" -> "MPEG-2"
        "audio/mp4a-latm", "audio/aac" -> "AAC"
        "audio/ac3" -> "AC-3"
        "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
        "audio/ac4" -> "AC-4"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-l1", "audio/mpeg-l2" -> "MP2"
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd;profile=p2" -> "DTS"
        "audio/opus" -> "Opus"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        else -> mime.substringAfter('/').uppercase().takeIf { it.isNotBlank() }
    }
}

/**
 * The external sources that played badly on this device (an error, no sound, a long stall), per
 * channel: the player tries them last. Kept on the device, never sent.
 */
interface ExternalSourceMemory {
    fun isBad(channelId: Long, url: String): Boolean

    fun markBad(channelId: Long, url: String)

    fun markGood(channelId: Long, url: String)
}

/** The sources in the order to try them: the good ones as they are, then the bad ones. */
fun ExternalSourceMemory.ordered(channelId: Long, sources: List<StreamSource>): List<StreamSource> {
    val (bad, good) = sources.partition { isBad(channelId, it.url) }
    return good + bad
}

/** [ExternalSourceMemory] for tests and previews (and the default of a session). */
class InMemoryExternalSourceMemory : ExternalSourceMemory {
    private val bad = mutableSetOf<Pair<Long, String>>()

    override fun isBad(channelId: Long, url: String) = (channelId to url) in bad

    override fun markBad(channelId: Long, url: String) {
        bad += channelId to url
    }

    override fun markGood(channelId: Long, url: String) {
        bad -= channelId to url
    }
}

/**
 * What an external channel does when a source fails or plays without sound: the next source; with
 * none left, the first one that played (silently) with a note rather than nothing; else it fails.
 */
object ExternalFallback {
    sealed interface Step {
        data class TryNext(val index: Int) : Step

        /** Play this source although it has no sound the device plays, and say so. */
        data class AcceptSilent(val index: Int) : Step

        data object GiveUp : Step
    }

    fun afterFailure(index: Int, count: Int, silent: Set<Int>): Step = when {
        index + 1 < count -> Step.TryNext(index + 1)
        else -> silent.filter { it != index }.minOrNull()?.let(Step::AcceptSilent) ?: Step.GiveUp
    }

    fun afterSilence(index: Int, count: Int): Step =
        if (index + 1 < count) Step.TryNext(index + 1) else Step.AcceptSilent(index)
}

package app.antresol.core.model

import kotlinx.serialization.Serializable

/** What the player can decode; sent as `caps` to `/api/play`. Without caps the server transcodes everything. */
@Serializable
data class PlaybackCaps(
    val videoCodecs: List<String> = emptyList(),
    val audioCodecs: List<String> = emptyList(),
    val containers: List<String> = emptyList(),
    val maxHeight: Int? = null,
    val maxBitrate: Long? = null,
    val hevcNeedsHvc1: Boolean? = null,
) {
    companion object {
        /** A safe ExoPlayer baseline; apps refine it from MediaCodecList. `mkv` enables direct MKV play. */
        val EXOPLAYER_BASELINE = PlaybackCaps(
            videoCodecs = listOf("h264", "hevc"),
            audioCodecs = listOf("aac", "mp3", "ac3", "eac3", "opus", "flac"),
            containers = listOf("mp4", "mkv", "webm"),
        )
    }
}

/** Values for [PlayRequest.quality]. */
object Quality {
    const val AUTO = "auto"
    const val ORIGINAL = "original"
    const val P1080 = "1080"
    const val P720 = "720"
    const val P480 = "480"
    val ALL = listOf(AUTO, ORIGINAL, P1080, P720, P480)
}

/** Body of `POST /api/play/{fileId}`. Null fields are omitted. */
@Serializable
data class PlayRequest(
    val caps: PlaybackCaps,
    val startSec: Double? = null,
    val quality: String? = null,
    val audioIndex: Int? = null,
    val replaceSession: String? = null,
    val burnSubtitle: Int? = null,
)

/** Values of [PlayResponse.mode]. */
object PlayMode {
    const val DIRECT = "direct"
    const val REMUX = "remux"
    const val TRANSCODE = "transcode"
}

@Serializable
data class PlayResponse(
    val mode: String,
    /** `/api/stream/{fileId}` for direct play or `/api/hls/{session}/index.m3u8`. */
    val url: String,
    val reason: String = "",
    /** Effective start. For HLS, playlist time 0 is this position in the file. */
    val startSec: Double = 0.0,
    val durationMs: Long = 0,
    val audioIndex: Int? = null,
    val audio: List<AudioTrack> = emptyList(),
    val subtitles: List<SubTrack> = emptyList(),
    val quality: QualityInfo = QualityInfo(),
    val hlsComplete: Boolean = false,
    val burnedSubtitle: Int? = null,
) {
    val isHls: Boolean get() = mode != PlayMode.DIRECT

    /** The HLS session id parsed from [url], or null for direct play. */
    val hlsSession: String? get() = hlsSessionFromUrl(url)

    companion object {
        private val HLS_URL = Regex("""/api/hls/([^/?#]+)/""")

        fun hlsSessionFromUrl(url: String): String? = HLS_URL.find(url)?.groupValues?.get(1)
    }
}

@Serializable
data class QualityInfo(
    val selected: String = "",
    val applied: String = "",
    val remote: Boolean = false,
)

/** `PUT /api/hls/{session}` keepalive response. */
@Serializable
data class HlsStatus(
    val segments: Int = 0,
    val running: Boolean = false,
    val paused: Boolean = false,
)

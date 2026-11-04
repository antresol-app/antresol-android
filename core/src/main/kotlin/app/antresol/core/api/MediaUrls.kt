package app.antresol.core.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder

/**
 * Builds absolute URLs for images and media. Images, subtitles and comic pages need the Bearer
 * header (see [AntresolHeadersInterceptor]); stream, HLS, audio and track URLs accept `?mk=`.
 */
class MediaUrls(baseUrl: String) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)

    /**
     * Resolves a server path the way the web app does: absolute URLs and `/paths` are used as is,
     * anything else is an image name under `/api/images/`. Returns null for `""` (no image).
     */
    fun resolve(path: String?): String? = when {
        path.isNullOrEmpty() -> null
        path.startsWith("http:") || path.startsWith("https:") || path.startsWith("data:") -> path
        path.startsWith("/") -> baseUrl + path
        else -> "$baseUrl/api/images/" + URLEncoder.encode(path, Charsets.UTF_8).replace("+", "%20")
    }

    /** Absolute image URL for a card `poster`/`backdrop`/`logo` or a `cover`, or null when empty. */
    fun image(path: String?): String? = resolve(path)

    /** The original file with Range support (`/api/stream/{fileId}`). */
    fun stream(fileId: Long, mediaKey: String? = null): String =
        build("api", "stream", fileId.toString()) { mk(mediaKey) }

    /** Download with `Content-Disposition`; Bearer only, `mk` is rejected here. */
    fun download(fileId: Long): String = build("api", "download", fileId.toString())

    /** Absolute URL for a `PlayResponse.url` (direct stream or HLS playlist), signed with [mediaKey]. */
    fun playback(url: String, mediaKey: String? = null): String =
        withMediaKey(requireNotNull(resolve(url)) { "Empty playback url" }, mediaKey)

    /** `/api/tracks/{trackId}/stream`. [accept] lists codecs the device decodes; [startSec] seeks a transcode. */
    fun track(
        trackId: Long,
        accept: List<String> = ANDROID_AUDIO_CODECS,
        format: String? = null,
        startSec: Double? = null,
        mediaKey: String? = null,
    ): String = build("api", "tracks", trackId.toString(), "stream") { audioParams(accept, format, startSec, mediaKey) }

    /** `/api/audio/{fileId}` for audiobook parts; same parameters as [track]. */
    fun audio(
        fileId: Long,
        accept: List<String> = ANDROID_AUDIO_CODECS,
        format: String? = null,
        startSec: Double? = null,
        mediaKey: String? = null,
    ): String = build("api", "audio", fileId.toString()) { audioParams(accept, format, startSec, mediaKey) }

    /** A comic page image; [index] is 0-based. Bearer only. */
    fun comicPage(fileId: Long, index: Int): String =
        build("api", "comics", fileId.toString(), "page", index.toString())

    /** All pages of a comic volume as a ZIP. Bearer only. */
    fun comicZip(fileId: Long): String = build("api", "comics", fileId.toString(), "pages.zip")

    /** Album ZIP download. Bearer only. */
    fun albumZip(albumId: Long): String = build("api", "music", "albums", albumId.toString(), "zip")

    fun withMediaKey(url: String, mediaKey: String?): String {
        if (mediaKey == null) return url
        return url.toHttpUrl().newBuilder().setQueryParameter("mk", mediaKey).build().toString()
    }

    private fun build(vararg segments: String, params: HttpUrl.Builder.() -> Unit = {}): String {
        val builder = baseUrl.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        builder.params()
        return builder.build().toString()
    }

    private fun HttpUrl.Builder.mk(mediaKey: String?) {
        if (mediaKey != null) addQueryParameter("mk", mediaKey)
    }

    private fun HttpUrl.Builder.audioParams(accept: List<String>, format: String?, startSec: Double?, mediaKey: String?) {
        // Codec names are plain tokens; keep the commas readable like the web app does.
        if (accept.isNotEmpty()) addEncodedQueryParameter("accept", accept.joinToString(",") { it.filter(Char::isLetterOrDigit) })
        if (format != null) addQueryParameter("format", format)
        if (startSec != null && startSec > 0) addQueryParameter("t", formatSeconds(startSec))
        mk(mediaKey)
    }

    companion object {
        /** Codecs ExoPlayer decodes on every Android device (spec section 11). */
        val ANDROID_AUDIO_CODECS = listOf("aac", "mp3", "flac", "opus", "vorbis")

        /** The server's default port, assumed when the user types a bare host. */
        const val DEFAULT_PORT = 8420

        /**
         * Turns user input such as `nas.local`, `192.168.1.5:8420/` or `https://media.example.com`
         * into a base URL without a trailing slash. A bare host gets `http://` and port [DEFAULT_PORT].
         */
        fun normalizeBaseUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Server address is empty" }
            val hasScheme = trimmed.contains("://")
            val parsed = (if (hasScheme) trimmed else "http://$trimmed").toHttpUrl()
            val hostPart = trimmed.substringAfter("://").substringBefore('/')
            val hasPort = Regex(""":\d+$""").containsMatchIn(hostPart)
            val result = if (!hasScheme && !hasPort) parsed.newBuilder().port(DEFAULT_PORT).build() else parsed
            return result.toString().trimEnd('/')
        }

        internal fun formatSeconds(sec: Double): String =
            if (sec == Math.floor(sec)) sec.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, sec)
    }
}

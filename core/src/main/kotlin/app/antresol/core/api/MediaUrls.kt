package app.antresol.core.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

    /**
     * A channel's live HLS playlist (`/api/channels/live/{id}/index.m3u8`). With [mediaKey] the server
     * signs every segment line with the same key, so the player needs no headers.
     */
    fun liveChannel(channelId: Long, mediaKey: String? = null): String =
        build("api", "channels", "live", channelId.toString(), "index.m3u8") { mk(mediaKey) }

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

        /** `host:port` of a server URL, as the apps show the server; [url] itself when it is not a URL. */
        fun hostPort(url: String): String = url.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: url

        /**
         * Turns user input such as `nas.local`, `192.168.1.5:8420/` or `https://media.example.com`
         * into a base URL without a trailing slash. A bare host gets `http://` and port [DEFAULT_PORT].
         */
        /** Throws [InvalidServerAddressException] for an empty or malformed address. */
        fun normalizeBaseUrl(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            if (trimmed.isEmpty()) throw InvalidServerAddressException("Server address is empty")
            val hasScheme = trimmed.contains("://")
            val parsed = try {
                (if (hasScheme) trimmed else "http://$trimmed").toHttpUrl()
            } catch (e: IllegalArgumentException) {
                throw InvalidServerAddressException(e.message ?: "Invalid server address", e)
            }
            val hostPart = trimmed.substringAfter("://").substringBefore('/')
            val hasPort = Regex(""":\d+$""").containsMatchIn(hostPart)
            val result = if (!hasScheme && !hasPort) parsed.newBuilder().port(DEFAULT_PORT).build() else parsed
            return result.toString().trimEnd('/')
        }

        /** The widths the server shrinks an image to (`?w=`; `imageWidths` in the server's images.go). */
        val IMAGE_WIDTHS = listOf(160, 240, 320, 480, 640, 960, 1280, 1920)

        /**
         * [url] at the server's smallest step at least [widthPx] wide, the rule of the web app. As it
         * is when no step is wide enough (the original then, never a smaller copy scaled up) and for
         * a URL with one size: not an image of the server at [baseUrl], a video frame
         * (`/api/images/frame/`), a URL that already has a query. And for a picture that may be
         * [transparent] (a logo): the server's smaller copies are JPEG, so it would come back on a
         * black box; the web app never sizes a logo either. The place that draws the image says so.
         */
        fun sizedImage(baseUrl: String, url: String, widthPx: Int, transparent: Boolean = false): String {
            val images = "$baseUrl/api/images/"
            if (transparent || widthPx <= 0 || !url.startsWith(images) || url.startsWith("${images}frame/") || url.contains('?')) return url
            val step = IMAGE_WIDTHS.firstOrNull { it >= widthPx } ?: return url
            return "$url?w=$step"
        }

        /**
         * The image width a [widthPx] by [heightPx] box needs. A picture that only has to fit inside
         * needs the box's width. One that fills the box and is cut to it ([crop]) may be wider than the
         * box (a 16:9 backdrop behind a tall phone hero), so it needs the width of the widest artwork
         * (16:9) at the box's height.
         */
        fun neededImageWidth(widthPx: Int, heightPx: Int, crop: Boolean): Int =
            if (crop) maxOf(widthPx, (heightPx * 16 + 8) / 9) else widthPx

        /**
         * The image width a [widthPx] by [heightPx] tile needs for artwork of about the tile's shape (a
         * poster in a poster tile). Cut to the tile ([crop]), a picture less tall than the tile (a
         * square cover or a 16:9 frame the server gives as the poster) is scaled to the tile's height, so
         * a tall tile asks for its height: enough for a square, and a wide picture is stretched 1.8
         * times instead of 2.7.
         */
        fun neededTileWidth(widthPx: Int, heightPx: Int, crop: Boolean): Int =
            if (crop) maxOf(widthPx, heightPx) else widthPx

        internal fun formatSeconds(sec: Double): String =
            if (sec == Math.floor(sec)) sec.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, sec)
    }
}

/** The server address typed by the user cannot be parsed as a URL. */
class InvalidServerAddressException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

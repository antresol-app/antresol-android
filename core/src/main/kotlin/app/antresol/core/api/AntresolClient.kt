package app.antresol.core.api

import app.antresol.core.model.AlbumDetail
import app.antresol.core.model.ArtistDetail
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Card
import app.antresol.core.model.ComicPages
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.DeviceSessions
import app.antresol.core.model.ErrorBody
import app.antresol.core.model.GenresResponse
import app.antresol.core.model.Health
import app.antresol.core.model.HlsStatus
import app.antresol.core.model.HomeResponse
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.LibraryStatus
import app.antresol.core.model.Lyrics
import app.antresol.core.model.Me
import app.antresol.core.model.MediaKey
import app.antresol.core.model.Page
import app.antresol.core.model.PlayRecord
import app.antresol.core.model.PlayRequest
import app.antresol.core.model.PlayResponse
import app.antresol.core.model.Playlist
import app.antresol.core.model.PlaylistCreate
import app.antresol.core.model.PlaylistDetail
import app.antresol.core.model.PlaylistList
import app.antresol.core.model.PlaylistPatch
import app.antresol.core.model.Profile
import app.antresol.core.model.Progress
import app.antresol.core.model.ProgressUpdate
import app.antresol.core.model.SearchResults
import app.antresol.core.model.SetupStatus
import app.antresol.core.model.ShelvesResponse
import app.antresol.core.model.ShuffleSource
import app.antresol.core.model.TokenRequest
import app.antresol.core.model.TokenResponse
import app.antresol.core.model.Track
import app.antresol.core.model.TrackList
import app.antresol.core.model.WatchedRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client for the native `/api` of an Antresol server (spec: docs/API.md).
 *
 * One instance talks to one server. Set [token] after sign-in (the [signIn] call does it for you);
 * every request then carries `Authorization: Bearer`, plus `Accept-Language` from [language].
 * [httpClient] carries the same headers for this server only and can be shared with Coil and ExoPlayer.
 *
 * Every call throws [ApiException] for non-2xx answers and [java.io.IOException] for network errors.
 * A 401 on an authenticated call also invokes [onUnauthorized] so apps can return to the profile picker.
 */
class AntresolClient(
    baseUrl: String,
    httpClient: OkHttpClient = defaultHttpClient(),
    language: () -> String = { ServerLanguage.EN },
    @Volatile var token: String? = null,
    private val onUnauthorized: (failedToken: String) -> Unit = {},
) {
    val urls = MediaUrls(baseUrl)
    val baseUrl: String get() = urls.baseUrl

    private val baseHttpClient = httpClient
    private val language = language

    val httpClient: OkHttpClient = httpClient.newBuilder()
        .addInterceptor(AntresolHeadersInterceptor({ urls.baseUrl }, { token }, language))
        .build()

    /** Media key for `?mk=` URLs, refreshed automatically and bound to the current [token]. */
    val mediaKeys = MediaKeyCache(fetch = ::mediaKey, token = { token })

    /**
     * A client for the same server that keeps sending [token] even after this client's token changes
     * (profile switch, change server). Used for final writes such as the last progress save, which can
     * run after the profile was switched. Its 401s are reported here with the pinned token.
     */
    fun pinned(token: String? = this.token): AntresolClient =
        AntresolClient(baseUrl, baseHttpClient, language, token, onUnauthorized)

    // region Auth and profiles

    suspend fun health(): Health = get("api/health")

    suspend fun setup(): SetupStatus = get("api/setup")

    suspend fun profiles(): List<Profile> = get("api/profiles")

    /** Exchanges a profile (and password) for a device token, and starts using it. */
    suspend fun signIn(userId: Long, password: String?, device: DeviceInfo): TokenResponse {
        val body = TokenRequest(userId = userId, password = password?.takeIf { it.isNotEmpty() }, device = device)
        val response: TokenResponse = post("api/auth/token", jsonBody(body), reportUnauthorized = false)
        token = response.token
        return response
    }

    suspend fun me(): Me = get("api/me")

    /** Revokes the current token on the server and forgets it locally. */
    suspend fun logout() {
        try {
            sendNoContent("POST", url("api/logout"), null)
        } finally {
            token = null
            mediaKeys.invalidate()
        }
    }

    suspend fun mediaKey(): MediaKey = post("api/auth/media-key", null)

    suspend fun devices(): DeviceSessions = get("api/me/devices")

    suspend fun revokeDevice(sessionId: Long) = delete("api/me/devices/$sessionId")

    // endregion

    // region Library

    suspend fun home(): HomeResponse = get("api/home")

    /** Movies and series. [kind] is `movie`, `series` or null for both; [sort] `recent`, `title` or `year`. */
    suspend fun items(kind: String? = null, sort: String? = null, limit: Int = PAGE_SIZE, offset: Int = 0): Page<Card> =
        get("api/items") {
            addOptional("kind", kind)
            addOptional("sort", sort)
            addQueryParameter("limit", limit.toString())
            addQueryParameter("offset", offset.toString())
        }

    suspend fun item(id: Long): ItemDetail = get("api/items/$id")

    suspend fun search(query: String): SearchResults = get("api/search") { addQueryParameter("q", query) }

    suspend fun libraryStatus(): LibraryStatus = get("api/library/status")

    // endregion

    // region Progress, watched, watchlist

    suspend fun progress(itemId: Long): Progress = get("api/progress/$itemId")

    suspend fun saveProgress(itemId: Long, update: ProgressUpdate) =
        sendNoContent("PUT", url("api/progress/$itemId"), jsonBody(update))

    suspend fun setWatched(itemId: Long, watched: Boolean) =
        sendNoContent("POST", url("api/items/$itemId/watched"), jsonBody(WatchedRequest(watched)))

    suspend fun addToWatchlist(itemId: Long) = sendNoContent("POST", url("api/watchlist/$itemId"), null)

    suspend fun removeFromWatchlist(itemId: Long) = delete("api/watchlist/$itemId")

    // endregion

    // region Video playback

    /** Asks how to play a **file** id. Throws [ApiException] with status 429 when the profile has 2 HLS sessions. */
    suspend fun play(fileId: Long, request: PlayRequest): PlayResponse = post("api/play/$fileId", jsonBody(request))

    suspend fun hlsKeepalive(session: String): HlsStatus = send("PUT", url("api/hls/$session"), null)

    suspend fun hlsStop(session: String) = delete("api/hls/$session")

    /** Downloads a WebVTT subtitle (`SubTrack.url`); subtitles need the Bearer header, not `mk`. */
    suspend fun subtitleVtt(url: String): String =
        execute(Request.Builder().url(requireNotNull(urls.resolve(url))).get().build()).body

    // endregion

    // region Music

    suspend fun musicHome(): ShelvesResponse = get("api/music/home")

    suspend fun albums(
        sort: String? = null,
        genre: String? = null,
        artistId: Long? = null,
        starred: Boolean = false,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): Page<Card> = get("api/music/albums") {
        addOptional("sort", sort)
        addOptional("genre", genre)
        addOptional("artist", artistId?.toString())
        if (starred) addQueryParameter("starred", "1")
        addQueryParameter("limit", limit.toString())
        addQueryParameter("offset", offset.toString())
    }

    suspend fun album(id: Long): AlbumDetail = get("api/music/albums/$id")

    suspend fun artists(starred: Boolean = false, limit: Int = PAGE_SIZE, offset: Int = 0): Page<Card> =
        get("api/music/artists") { paging(starred, limit, offset) }

    suspend fun artist(id: Long): ArtistDetail = get("api/music/artists/$id")

    suspend fun songs(starred: Boolean = false, limit: Int = PAGE_SIZE, offset: Int = 0): Page<Track> =
        get("api/music/songs") { paging(starred, limit, offset) }

    suspend fun track(id: Long): Track = get("api/music/tracks/$id")

    suspend fun genres(): GenresResponse = get("api/music/genres")

    suspend fun shuffle(source: ShuffleSource = ShuffleSource.All): TrackList = get("api/music/shuffle") {
        when (source) {
            ShuffleSource.All -> addQueryParameter("source", "all")
            is ShuffleSource.Genre -> addQueryParameter("source", "genre").addQueryParameter("genre", source.name)
            is ShuffleSource.Artist -> addQueryParameter("source", "artist").addQueryParameter("artist", source.artistId.toString())
        }
    }

    /** Lyrics of a track, or null when the server has none (404). */
    suspend fun lyrics(trackId: Long): Lyrics? = try {
        get("api/music/tracks/$trackId/lyrics")
    } catch (e: ApiException) {
        if (e.isNotFound) null else throw e
    }

    /** Records a play (scrobble). [at] is an optional RFC 3339 time for offline plays. */
    suspend fun recordPlay(trackId: Long, at: String? = null) =
        sendNoContent("POST", url("api/music/plays"), jsonBody(PlayRecord(trackId, at)))

    /** Favorites a track, album or artist. */
    suspend fun star(itemId: Long) = sendNoContent("POST", url("api/music/star/$itemId"), null)

    suspend fun unstar(itemId: Long) = delete("api/music/star/$itemId")

    suspend fun playlists(): PlaylistList = get("api/music/playlists")

    suspend fun playlist(id: Long): PlaylistDetail = get("api/music/playlists/$id")

    suspend fun createPlaylist(name: String, trackIds: List<Long>? = null): Playlist =
        post("api/music/playlists", jsonBody(PlaylistCreate(name, trackIds)))

    suspend fun updatePlaylist(id: Long, patch: PlaylistPatch): Playlist =
        send("PATCH", url("api/music/playlists/$id"), jsonBody(patch))

    suspend fun deletePlaylist(id: Long) = delete("api/music/playlists/$id")

    // endregion

    // region Books, audiobooks, comics

    suspend fun booksHome(): ShelvesResponse = get("api/books/home")

    /** [kind] is required: `book`, `audiobook` or `comic`; [sort] `added`, `title` or `author`. */
    suspend fun books(kind: String, sort: String? = null, limit: Int = PAGE_SIZE, offset: Int = 0): Page<Card> =
        get("api/books") {
            addQueryParameter("kind", kind)
            addOptional("sort", sort)
            addQueryParameter("limit", limit.toString())
            addQueryParameter("offset", offset.toString())
        }

    suspend fun book(id: Long): BookDetail = get("api/books/$id")

    suspend fun comicPages(fileId: Long): ComicPages = get("api/comics/$fileId/pages")

    // endregion

    // region Plumbing

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        "${urls.baseUrl}/$path".toHttpUrl().newBuilder().apply(query).build()

    private suspend inline fun <reified T> get(path: String, noinline query: HttpUrl.Builder.() -> Unit = {}): T =
        send("GET", url(path, query), null)

    private suspend inline fun <reified T> post(path: String, body: RequestBody?, reportUnauthorized: Boolean = true): T =
        decode(execute(buildRequest("POST", url(path), body), reportUnauthorized).body, serializer())

    private suspend inline fun <reified T> send(method: String, url: HttpUrl, body: RequestBody?): T =
        decode(execute(buildRequest(method, url, body)).body, serializer())

    private suspend fun delete(path: String) = sendNoContent("DELETE", url(path), null)

    private suspend fun sendNoContent(method: String, url: HttpUrl, body: RequestBody?) {
        execute(buildRequest(method, url, body))
    }

    private inline fun <reified T> jsonBody(value: T): RequestBody = json.encodeToString(value).toRequestBody(JSON)

    private fun buildRequest(method: String, url: HttpUrl, body: RequestBody?): Request {
        // OkHttp needs a body for POST/PUT/PATCH; the server accepts an empty one without Content-Type.
        val effectiveBody = body ?: if (method == "GET" || method == "DELETE") null else EMPTY_BODY
        return Request.Builder().url(url).method(method, effectiveBody).build()
    }

    private suspend fun execute(request: Request, reportUnauthorized: Boolean = true): RawResponse {
        // Send the token read now, so a 401 is reported for the token that actually failed.
        // Never hand the token to another host (a server-supplied absolute URL, e.g. a subtitle).
        val sentToken = token?.takeIf { request.url.isSameOrigin(urls.baseUrl.toHttpUrl()) }
        val authorized = if (sentToken == null) request else request.newBuilder().header("Authorization", "Bearer $sentToken").build()
        val response = httpClient.newCall(authorized).awaitRaw()
        if (response.status in 200..299) return response
        val error = errorFrom(response)
        if (error.isUnauthorized && reportUnauthorized && sentToken != null) onUnauthorized(sentToken)
        throw error
    }

    private fun errorFrom(response: RawResponse): ApiException {
        val body = runCatching { json.decodeFromString<ErrorBody>(response.body) }.getOrNull()
        val message = body?.error?.takeIf { it.isNotBlank() } ?: "HTTP ${response.status}"
        return ApiException(
            status = response.status,
            message = message,
            code = body?.code,
            retryAfterSec = response.headers["Retry-After"]?.trim()?.toLongOrNull(),
        )
    }

    private fun <T> decode(body: String, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        throw ApiException(status = 200, message = "Unexpected server response: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw ApiException(status = 200, message = "Unexpected server response: ${e.message}")
    }

    private fun HttpUrl.Builder.addOptional(name: String, value: String?) {
        if (!value.isNullOrEmpty()) addQueryParameter(name, value)
    }

    private fun HttpUrl.Builder.paging(starred: Boolean, limit: Int, offset: Int) {
        if (starred) addQueryParameter("starred", "1")
        addQueryParameter("limit", limit.toString())
        addQueryParameter("offset", offset.toString())
    }

    // endregion

    companion object {
        /** Page size the web app uses everywhere `total` is returned. */
        const val PAGE_SIZE = 120

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        /** Lenient JSON: unknown keys ignored, nulls coerced to defaults, null fields omitted from bodies. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = true
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

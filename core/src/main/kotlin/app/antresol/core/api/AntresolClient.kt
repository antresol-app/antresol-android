package app.antresol.core.api

import app.antresol.core.model.AddonRef
import app.antresol.core.model.AlbumDetail
import app.antresol.core.model.AppInfo
import app.antresol.core.model.ArtistDetail
import app.antresol.core.model.AutoSkipRequest
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Card
import app.antresol.core.model.ChannelEpg
import app.antresol.core.model.ChannelGuide
import app.antresol.core.model.ChannelList
import app.antresol.core.model.ChannelPrefs
import app.antresol.core.model.ComicPages
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.DeviceSessions
import app.antresol.core.model.DeviceHistoryRequest
import app.antresol.core.model.DeviceHistoryResult
import app.antresol.core.model.ManagedAccount
import app.antresol.core.model.ManagedAccounts
import app.antresol.core.model.ManagedCreateRequest
import app.antresol.core.model.EpisodeGuideResponse
import app.antresol.core.model.ErrorBody
import app.antresol.core.model.GenresResponse
import app.antresol.core.model.Health
import app.antresol.core.model.HlsStatus
import app.antresol.core.model.HomeResponse
import app.antresol.core.model.InputInfo
import app.antresol.core.model.InputPlay
import app.antresol.core.model.InputProgressBody
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.TrailerList
import app.antresol.core.model.TrailerPlay
import app.antresol.core.model.LastChannelRequest
import app.antresol.core.model.LibraryStatus
import app.antresol.core.model.Lyrics
import app.antresol.core.model.Me
import app.antresol.core.model.MediaKey
import app.antresol.core.model.Page
import app.antresol.core.model.PairApproveRequest
import app.antresol.core.model.PairApproved
import app.antresol.core.model.PairCodeRequest
import app.antresol.core.model.PairLookup
import app.antresol.core.model.PairPoll
import app.antresol.core.model.PairPollBody
import app.antresol.core.model.PairPollRequest
import app.antresol.core.model.PairStart
import app.antresol.core.model.PairStartRequest
import app.antresol.core.model.PlayRecord
import app.antresol.core.model.PlayRequest
import app.antresol.core.model.PlayResponse
import app.antresol.core.model.PlaybackPrefs
import app.antresol.core.model.Playlist
import app.antresol.core.model.PlaylistCreate
import app.antresol.core.model.PlaylistDetail
import app.antresol.core.model.PlaylistList
import app.antresol.core.model.PlaylistPatch
import app.antresol.core.model.Profile
import app.antresol.core.model.Progress
import app.antresol.core.model.ProgressUpdate
import app.antresol.core.model.Quality
import app.antresol.core.model.RunRequest
import app.antresol.core.model.RunResult
import app.antresol.core.model.QualityInfo
import app.antresol.core.model.SearchResults
import app.antresol.core.model.SetupStatus
import app.antresol.core.model.StepRequest
import app.antresol.core.model.TitleInfo
import app.antresol.core.model.KidAsk
import app.antresol.core.model.KidCandidate
import app.antresol.core.model.KidRequest
import app.antresol.core.model.RequestCount
import app.antresol.core.model.DeclineBody
import app.antresol.core.model.ShelvesResponse
import app.antresol.core.model.ShuffleSource
import app.antresol.core.model.TokenRequest
import app.antresol.core.model.TokenResponse
import app.antresol.core.model.Track
import app.antresol.core.model.TrackList
import app.antresol.core.model.UiLanguage
import app.antresol.core.model.UiLanguageRequest
import app.antresol.core.model.WatchedRequest
import app.antresol.core.model.WayCancelRequest
import app.antresol.core.model.WayProgressBody
import app.antresol.core.model.WaySearchResults
import app.antresol.core.model.WayStateResponse
import app.antresol.core.model.WayStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
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

    /** For calls the server may take long to answer (a trailer's first fetch). */
    private val slowHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder().readTimeout(SLOW_READ_TIMEOUT_SEC, TimeUnit.SECONDS).build()
    }

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

    // «Отдельная история на этом устройстве». A kid profile gets 403 on the writes (its switch is an
    // administrator's); the list it may read.

    /** `GET /api/me/managed`: the profile's separate histories. */
    suspend fun managedAccounts(): ManagedAccounts = get("api/me/managed")

    /** `POST /api/me/managed`: a new separate history; 400 for an empty name, 409 when there are too many. */
    suspend fun createManagedAccount(name: String): ManagedAccount =
        post("api/me/managed", jsonBody(ManagedCreateRequest(name)))

    /** `PUT /api/me/devices/current/history`: switches this device's history (see [DeviceHistoryRequest]). */
    suspend fun setDeviceHistory(request: DeviceHistoryRequest): DeviceHistoryResult =
        send("PUT", url("api/me/devices/current/history"), jsonBody(request))

    // endregion

    // region QR sign-in (TV pairing)

    /** TV, no auth: opens a pairing request. 429 (with `Retry-After`) when this address already has 3 open. */
    suspend fun pairStart(device: DeviceInfo): PairStart =
        post("api/auth/pair/start", jsonBody(PairStartRequest(device)), reportUnauthorized = false)

    /** TV, no auth: one poll. 410 is an answer here (denied / expired), not an error. */
    suspend fun pairPoll(secret: String): PairPoll {
        val request = buildRequest("POST", url("api/auth/pair/poll"), jsonBody(PairPollRequest(secret)))
        val response = httpClient.newCall(request).awaitRaw()
        val body = runCatching { json.decodeFromString(PairPollBody.serializer(), response.body) }.getOrNull()
        return when {
            response.status == 200 && body?.status == "approved" && body.token != null && body.user != null ->
                PairPoll.Approved(body.token, body.user)
            response.status == 200 && body?.status == "pending" -> PairPoll.Pending(body.interval)
            response.status == 410 && body?.status == "denied" -> PairPoll.Denied(body.error)
            response.status == 410 -> PairPoll.Expired(body?.error)
            response.status == 429 -> PairPoll.Throttled(response.headers["Retry-After"]?.trim()?.toLongOrNull() ?: 30)
            response.status == 200 -> throw ApiException(status = 200, message = "Unexpected pairing answer")
            else -> throw errorFrom(response)
        }
    }

    /** Signed in: what asks to sign in with [code] (any case, spaces and dashes ignored). 404 = unknown or used. */
    suspend fun pairLookup(code: String): PairLookup = post("api/auth/pair/lookup", jsonBody(PairCodeRequest(code)))

    /** Signed in: lets the TV in as [userId] (0 = this profile; another profile only for admins). */
    suspend fun pairApprove(code: String, userId: Long = 0): PairApproved =
        post("api/auth/pair/approve", jsonBody(PairApproveRequest(code, userId)))

    /** Signed in: refuses the request; the TV shows "declined". */
    suspend fun pairDeny(code: String) = sendNoContent("POST", url("api/auth/pair/deny"), jsonBody(PairCodeRequest(code)))

    // endregion

    // region Library

    /** Only the cards an app can open, each once ([HomeResponse.openable]): every screen built on Home relies on it. */
    suspend fun home(): HomeResponse = get<HomeResponse>("api/home").openable()

    /** Movies and series. [kind] is `movie`, `series` or null for both; [sort] `recent`, `title` or `year`. */
    suspend fun items(kind: String? = null, sort: String? = null, limit: Int = PAGE_SIZE, offset: Int = 0): Page<Card> =
        get("api/items") {
            addOptional("kind", kind)
            addOptional("sort", sort)
            addQueryParameter("limit", limit.toString())
            addQueryParameter("offset", offset.toString())
        }

    suspend fun item(id: Long): ItemDetail = get("api/items/$id")

    /**
     * YouTube trailers and teasers of a movie or series (a season or episode answers with its
     * series'), best first. The server answers an empty list when it has none.
     */
    suspend fun trailers(id: Long): TrailerList = get("api/items/$id/trailers")

    /**
     * `POST` a trailer's [app.antresol.core.model.Trailer.play] path: the server fetches the trailer
     * into its cache (up to about a minute the first time) and answers where to play it. 503 with
     * `Retry-After` while it is busy, 502 when the fetch failed, 404 for a key it never listed.
     * [app.antresol.core.playback.TrailerPlayback.open] adds the retries.
     */
    suspend fun trailerPlay(play: String): TrailerPlay {
        val url = requireNotNull(urls.resolve(play)) { "Empty trailer play path" }.toHttpUrl()
        return decodeOffMain(execute(buildRequest("POST", url, null), http = slowHttpClient).body, serializer())
    }

    suspend fun search(query: String): SearchResults = get("api/search") { addQueryParameter("q", query) }

    suspend fun libraryStatus(): LibraryStatus = get("api/library/status")

    /**
     * Every episode of a title by its TMDB id ([kind] `series` or `movie`), server 0.5.0+. Older
     * servers answer 404/405; 404 also when the age filter hides the title.
     * [app.antresol.core.library.EpisodeGuide.fetch] turns every failure into "no guide".
     */
    suspend fun episodeGuide(kind: String, tmdbId: Long): EpisodeGuideResponse = get("api/tracking/$kind/$tmdbId/episodes")

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

    /**
     * Downloads file [fileId] (`/api/stream/{fileId}`, Bearer header) into [target], blocking an
     * OkHttp thread while it copies. Errors are reported like any call (401 → [onUnauthorized],
     * the server's localized `error` in [ApiException]).
     */
    suspend fun download(fileId: Long, target: File) {
        val request = Request.Builder().url(urls.stream(fileId)).get().build()
        val sentToken = token
        val authorized = if (sentToken == null) request else request.newBuilder().header("Authorization", "Bearer $sentToken").build()
        val failure = httpClient.newCall(authorized).awaitStreamed { response ->
            val body = response.body ?: throw IOException("Empty response")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        } ?: return
        val error = errorFrom(failure)
        if (error.isUnauthorized && sentToken != null) onUnauthorized(sentToken)
        throw error
    }

    /** Downloads a WebVTT subtitle (`SubTrack.url`); subtitles need the Bearer header, not `mk`. */
    suspend fun subtitleVtt(url: String): String =
        execute(Request.Builder().url(requireNotNull(urls.resolve(url))).get().build()).body

    // endregion

    // region Music

    suspend fun musicHome(): ShelvesResponse = get<ShelvesResponse>("api/music/home").openable()

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

    suspend fun booksHome(): ShelvesResponse = get<ShelvesResponse>("api/books/home").openable()

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

    // region Live channels

    /** `GET /api/channels`: the channels this profile may watch (kid profiles get only theirs). */
    suspend fun channels(): ChannelList = get("api/channels")

    /** `GET /api/channels/{id}/guide`: the programme on air and the next [hours] of the channel. */
    suspend fun channelGuide(id: Long, hours: Int = 6): ChannelGuide =
        get("api/channels/$id/guide") { addQueryParameter("hours", hours.toString()) }

    /**
     * `GET /api/channels/epg`: every channel with the programmes overlapping `[from, from + hours)`.
     * [fromIso] is RFC 3339 (the server clamps it to now − 12 h … now + 47 h); null asks for now.
     */
    suspend fun channelEpg(fromIso: String?, hours: Int = 6): ChannelEpg =
        get("api/channels/epg") {
            addOptional("from", fromIso)
            addQueryParameter("hours", hours.coerceIn(1, 24).toString())
        }

    /** `GET /api/channels/prefs`: the profile's choices for its channel list (404 on an older server). */
    suspend fun channelPrefs(): ChannelPrefs = get("api/channels/prefs")

    /** `PUT /api/channels/prefs`: stores them; the answer is what was stored. */
    suspend fun setChannelPrefs(prefs: ChannelPrefs): ChannelPrefs = send("PUT", url("api/channels/prefs"), jsonBody(prefs))

    /** Marks a channel as a favourite of this profile (`PUT`), or not (`DELETE`); both idempotent. */
    suspend fun setChannelFavorite(id: Long, favorite: Boolean) =
        sendNoContent(if (favorite) "PUT" else "DELETE", url("api/channels/$id/favorite"), null)

    /**
     * `PUT /api/channels/last`: the channel just tuned in becomes the profile's «last channel». It is
     * a TV's last channel, not media progress: the server writes nothing else.
     */
    suspend fun setLastChannel(id: Long) =
        sendNoContent("PUT", url("api/channels/last"), jsonBody(LastChannelRequest(id)))

    /** `GET /api/me/ui-language`: the profile's interface language and the one the apps use. */
    suspend fun uiLanguage(): UiLanguage = get("api/me/ui-language")

    /** `PUT /api/me/ui-language`: "ru", "en", or "" for the profile's own language. Returns the new state. */
    suspend fun setUiLanguage(language: String): UiLanguage =
        send("PUT", url("api/me/ui-language"), jsonBody(UiLanguageRequest(language)))

    /** `GET /api/me/playback-prefs`: the profile's audio and subtitle languages. */
    suspend fun playbackPrefs(): PlaybackPrefs = get("api/me/playback-prefs")

    /** `PUT /api/me/playback-prefs/auto-skip`: changes only the kinds given; returns the profile's prefs. */
    suspend fun setAutoSkip(request: AutoSkipRequest): PlaybackPrefs =
        send("PUT", url("api/me/playback-prefs/auto-skip"), jsonBody(request))

    /**
     * Opens a channel's live stream and returns its playlist URL signed with a media key for the
     * player. The first request starts the channel's encoder: the server may hold it for a while or
     * answer 503 with `Retry-After` (see [app.antresol.core.channels.LiveStartPolicy]); any non-2xx
     * answer is thrown as [ApiException] with the server's message.
     */
    suspend fun openLiveChannel(id: Long, quality: String = Quality.AUTO): String {
        val url = urls.liveChannel(id, mediaKeys.get(LIVE_KEY_LIFETIME), quality)
        execute(buildRequest("GET", url.toHttpUrl(), null))
        return url
    }

    /**
     * `GET /api/channels/live/quality?q=`: the quality the server applies to our channels for the
     * choice [quality] (`applied`, and `remote` when the device is away from home).
     */
    suspend fun liveQuality(quality: String): QualityInfo =
        send("GET", urls.liveQuality(quality).toHttpUrl(), null)

    /**
     * The URL the player opens for an external channel's [url]: a provider URL as it is (the media
     * key never goes to another host), the server's hidden-address form (`/api/channels/live/ext/…`,
     * a 302 to the provider) made absolute and signed with the media key like other media URLs.
     */
    suspend fun externalStreamUrl(url: String): String {
        val absolute = requireNotNull(urls.resolve(url)) { "Empty stream url" }
        val parsed = absolute.toHttpUrlOrNull() ?: return absolute
        if (!parsed.isSameOrigin(urls.baseUrl.toHttpUrl())) return absolute
        return urls.withMediaKey(absolute, mediaKeys.get(LIVE_KEY_LIFETIME))
    }

    /**
     * The URL the player opens for an external channel's relay [path] (`stream.relay`, a path of
     * this server): absolute and without a media key. The relay's playlists link to relay paths
     * that carry none, so the player sends the app's Bearer with every request (same origin only).
     */
    fun relayStreamUrl(path: String): String = requireNotNull(urls.resolve(path)) { "Empty relay path" }

    /**
     * `POST /api/channels/opened`: «ТВ» opened, so the server refreshes the external sources marked
     * «при открытии» (when their last refresh is older than an hour). Writes nothing of the profile.
     */
    suspend fun channelsOpened() = sendNoContent("POST", url("api/channels/opened"), null)

    // endregion


    // region Addon ways (the `x/app` protocol, see docs/API.md)

    /** `GET /api/addons/web`: the addons open to this profile (a kid profile gets 403). */
    suspend fun addonsWeb(): List<AddonRef> = get("api/addons/web")

    /** `GET x/app` of an addon: what it offers the apps. 404 when it has no part for them. */
    suspend fun appInfo(addonId: String): AppInfo = send("GET", addonUrl(addonId, "app"), null)

    /** `GET x/app/search?q=`: the titles an addon finds. */
    suspend fun waySearch(addonId: String, query: String): WaySearchResults =
        send("GET", addonUrl(addonId, "app", "search") { addQueryParameter("q", query) }, null)

    /** `POST x/app/step`: one step of a choice; it may take seconds (the addon looks for sources). */
    suspend fun wayStep(addonId: String, request: StepRequest): WayStep =
        sendSlow("POST", addonUrl(addonId, "app", "step"), jsonBody(request))

    /** `POST x/app/run`: runs a way; the answer says what to do next ([RunResult]). */
    suspend fun wayRun(addonId: String, request: RunRequest): RunResult =
        sendSlow("POST", addonUrl(addonId, "app", "run"), jsonBody(request))

    /** `PUT x/app/progress`: the position of a stream an addon serves itself. */
    suspend fun wayProgress(addonId: String, body: WayProgressBody) =
        sendNoContent("PUT", addonUrl(addonId, "app", "progress"), jsonBody(body))

    /** `DELETE x/app/play/{session}`: closes the addon's session of a stream. */
    suspend fun wayStop(addonId: String, session: String) =
        sendNoContent("DELETE", addonUrl(addonId, "app", "play", session), null)

    /** `GET x/app/state`: the state of the addon's ways for a title ([season] for one season of a series). */
    suspend fun wayState(addonId: String, kind: String, tmdbId: Long, season: Int? = null): WayStateResponse =
        send(
            "GET",
            addonUrl(addonId, "app", "state") {
                addQueryParameter("kind", kind)
                addQueryParameter("tmdbId", tmdbId.toString())
                if (season != null) addQueryParameter("season", season.toString())
            },
            null,
        )

    /** `POST x/app/cancel`: stops what a way started (`done` says what to show). */
    suspend fun wayCancel(addonId: String, request: WayCancelRequest): RunResult =
        send("POST", addonUrl(addonId, "app", "cancel"), jsonBody(request))

    /** `GET /api/play/input/{id}`: what the input is and where it starts; 404 when it is gone. */
    suspend fun inputInfo(id: String): InputInfo = send("GET", url(listOf("api", "play", "input", id)), null)

    /**
     * `POST /api/play/input/{id}`: how to play an input, as `/api/play` answers for a file. While the
     * server still prepares it, it answers 202 `{pending:true}`: [InputPlay.Pending], to ask again.
     * [app.antresol.core.ways.InputPlayback.open] adds the waiting.
     */
    suspend fun inputPlay(id: String, request: PlayRequest): InputPlay {
        val raw = execute(buildRequest("POST", url(listOf("api", "play", "input", id)), jsonBody(request)), http = slowHttpClient)
        if (raw.status == 202) return InputPlay.Pending
        return InputPlay.Ready(decodeOffMain(raw.body, serializer<PlayResponse>()))
    }

    /** `PUT /api/play/input/{id}/progress`; [InputProgressBody.keepalive] marks the write of a closing player. */
    suspend fun inputProgress(id: String, body: InputProgressBody) =
        sendNoContent("PUT", url(listOf("api", "play", "input", id, "progress")), jsonBody(body))

    /** `GET /api/title/{kind}/{tmdbId}`: a movie or series by its TMDB id, in the library or not. */
    suspend fun title(kind: String, tmdbId: Long): TitleInfo = send("GET", url(listOf("api", "title", kind, tmdbId.toString())), null)

    // endregion

    // region Kid requests («Попросить», see the model in Requests.kt)

    /** `GET /api/requests/search?q=&kind=`: what a kid may ask for, of one [RequestSearchKind]; 403 for anyone else or while requests are closed. */
    suspend fun requestSearch(query: String, kind: String): List<KidCandidate> =
        send(
            "GET",
            url("api/requests/search") {
                addQueryParameter("q", query)
                addQueryParameter("kind", kind)
            },
            null,
        )

    /** `POST /api/requests`: the kid's request of the title, made if it has none (asking again returns it); 403 code `rating` above the age limit. */
    suspend fun askRequest(ask: KidAsk): KidRequest = post("api/requests", jsonBody(ask))

    /** `GET /api/requests/mine`: the profile's requests, newest first. */
    suspend fun myRequests(): List<KidRequest> = get("api/requests/mine")

    /** `GET /api/requests` (admins): the pending requests, then those answered in the last 30 days, each with who asked. */
    suspend fun allRequests(): List<KidRequest> = get("api/requests")

    /** `GET /api/requests/count` (admins): how many wait for an answer. */
    suspend fun requestCount(): RequestCount = get("api/requests/count")

    /** `POST /api/requests/{id}/approve` (admins): approved without a download (a download of the title approves it too). */
    suspend fun approveRequest(id: Long): KidRequest = send("POST", url(listOf("api", "requests", id.toString(), "approve")), null)

    /** `POST /api/requests/{id}/decline` (admins): [reason] is what the kid sees (may be empty); 409 when it was answered already. */
    suspend fun declineRequest(id: Long, reason: String): KidRequest =
        send("POST", url(listOf("api", "requests", id.toString(), "decline")), jsonBody(DeclineBody(reason)))

    // endregion

    // region Plumbing

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        "${urls.baseUrl}/$path".toHttpUrl().newBuilder().apply(query).build()

    private fun url(segments: List<String>, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl {
        val builder = urls.baseUrl.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.apply(query).build()
    }

    /** `/api/addons/{addonId}/x/<segments>`: [addonId] is the server's own text, so it is encoded as a segment. */
    private fun addonUrl(addonId: String, vararg segments: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        url(listOf("api", "addons", addonId, "x") + segments, query)

    private suspend inline fun <reified T> sendSlow(method: String, url: HttpUrl, body: RequestBody?): T =
        decodeOffMain(execute(buildRequest(method, url, body), http = slowHttpClient).body, serializer())

    private suspend inline fun <reified T> get(path: String, noinline query: HttpUrl.Builder.() -> Unit = {}): T =
        send("GET", url(path, query), null)

    private suspend inline fun <reified T> post(path: String, body: RequestBody?, reportUnauthorized: Boolean = true): T =
        decodeOffMain(execute(buildRequest("POST", url(path), body), reportUnauthorized).body, serializer())

    private suspend inline fun <reified T> send(method: String, url: HttpUrl, body: RequestBody?): T =
        decodeOffMain(execute(buildRequest(method, url, body)).body, serializer())

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

    private suspend fun execute(
        request: Request,
        reportUnauthorized: Boolean = true,
        http: OkHttpClient = httpClient,
    ): RawResponse {
        // Send the token read now, so a 401 is reported for the token that actually failed.
        // Never hand the token to another host (a server-supplied absolute URL, e.g. a subtitle).
        val sentToken = token?.takeIf { request.url.isSameOrigin(urls.baseUrl.toHttpUrl()) }
        val authorized = if (sentToken == null) request else request.newBuilder().header("Authorization", "Bearer $sentToken").build()
        val response = http.newCall(authorized).awaitRaw()
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

    /** Callers are on the main thread (view models); a page of cards or a channel list is parsed off it. */
    private suspend fun <T> decodeOffMain(body: String, serializer: KSerializer<T>): T =
        withContext(Dispatchers.Default) { decode(body, serializer) }

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

        /** A live channel can be watched for hours: its key is refreshed with less than this left. */
        private val LIVE_KEY_LIFETIME: java.time.Duration = java.time.Duration.ofHours(6)

        /** The server waits up to about 60 s for a trailer's first fetch. */
        private const val SLOW_READ_TIMEOUT_SEC = 90L

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

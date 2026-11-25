package app.antresol.core.model

import kotlinx.serialization.Serializable

// A kid profile asks a parent for a title the library does not have («Попросить»): it finds the title in the
// server's catalogue search, asks once, and sees the parents' answer. A parent answers in the apps or the web.
// The shapes are those of `/api/requests/*` and of the `kidRequest` field of `/api/title/…`.

/** The `status` of a [KidRequest]. */
object RequestStatus {
    /** Waiting for a parent. */
    const val PENDING = "pending"

    /** A parent said yes; the title is on its way. */
    const val APPROVED = "approved"

    /** The library has the title now (worked out when the request is shown, never stored). */
    const val IN_LIBRARY = "in_library"

    /** A parent said no ([KidRequest.reason] may say why). */
    const val DECLINED = "declined"
}

/** The `kind` of `GET /api/requests/search`: video is TMDB's movies and series, the rest the addons' catalogues. */
object RequestSearchKind {
    const val VIDEO = "video"
    const val MUSIC = "music"
    const val BOOK = "book"
    const val AUDIOBOOK = "audiobook"
    const val COMIC = "comic"
}

/** A kid's request, as the kid and the admins see it. */
@Serializable
data class KidRequest(
    val id: Long = 0,
    /** Names the title (`tmdb:603`, `ext:…`, `name:…`): one request per kid and key. */
    val key: String = "",
    /** `movie`, `series`, `music`, `book`, `audiobook` or `comic`. */
    val kind: String = "",
    val tmdbId: Long = 0,
    val extId: String = "",
    val title: String = "",
    /** The artist or the author. */
    val subtitle: String = "",
    val year: Int = 0,
    /** An absolute URL of a TMDB poster (movies and series only; "" for the rest). */
    val poster: String = "",
    val aliases: List<String> = emptyList(),
    /** The age rating, as the server knows it. */
    val rating: String = "",
    val status: String = RequestStatus.PENDING,
    /** Why a parent declined ("" when not said). */
    val reason: String = "",
    /** The library's copy once there is one ([RequestStatus.IN_LIBRARY]). */
    val libraryItemId: Long? = null,
    val createdAt: String = "",
    val decidedAt: String? = null,
    /** Who asked: in the admins' list only. */
    val user: RequestUser? = null,
    /** A kid's own request of a title above its limit now: no title or cover. */
    val hidden: Boolean = false,
) {
    val isVideo: Boolean get() = kind == Kind.MOVIE || kind == Kind.SERIES

    /** The title has a page of its own: a video the profile may see (a hidden one has none). */
    val hasTitlePage: Boolean get() = isVideo && !hidden && tmdbId > 0

    /** [key] and [kind] together: how a profile's requests are found by title. */
    val slot: String get() = requestSlot(kind, key)
}

@Serializable
data class RequestUser(
    val id: Long = 0,
    val name: String = "",
    val avatar: String = "",
    val photo: String = "",
)

/** The `kidRequest` field of a kid's search result or title page. */
@Serializable
data class KidRequestState(
    val key: String = "",
    /** May it be asked for (a search result's movie is checked when asked for). */
    val allowed: Boolean = true,
    /** Why not, in the profile's language. */
    val blocked: String? = null,
    val request: KidRequest? = null,
)

/** A title a kid may ask for, from `GET /api/requests/search`. */
@Serializable
data class KidCandidate(
    val kind: String = "",
    /** Movies and series. */
    val tmdbId: Long = 0,
    /** The catalogue's id (albums, books). */
    val extId: String = "",
    val title: String = "",
    /** The artist or the author ("" for manga). */
    val subtitle: String = "",
    val year: Int = 0,
    /** Movies and series only: albums and books come without a cover. */
    val poster: String = "",
    val explicit: Boolean = false,
    val aliases: List<String> = emptyList(),
    val kidRequest: KidRequestState? = null,
) {
    val isVideo: Boolean get() = kind == Kind.MOVIE || kind == Kind.SERIES

    /** [KidRequestState.key] and [kind] together (see [KidRequest.slot]). */
    val slot: String? get() = kidRequest?.key?.let { requestSlot(kind, it) }

    /** What `POST /api/requests` takes to ask for this title. */
    fun ask(): KidAsk =
        if (isVideo) KidAsk(kind = kind, tmdbId = tmdbId, title = title)
        else KidAsk(kind = kind, extId = extId, title = title, subtitle = if (kind == Kind.COMIC) "" else subtitle, year = year, aliases = aliases)
}

/** The body of `POST /api/requests`: a movie or series by its TMDB id, an album or book as the catalogue showed it. */
@Serializable
data class KidAsk(
    val kind: String,
    val tmdbId: Long = 0,
    val extId: String = "",
    val title: String = "",
    val subtitle: String = "",
    val year: Int = 0,
    val aliases: List<String> = emptyList(),
)

/** `GET /api/requests/count` (admins). */
@Serializable
data class RequestCount(val pending: Int = 0)

/** The body of `POST /api/requests/{id}/decline`. */
@Serializable
data class DeclineBody(val reason: String = "")

/** How a profile's requests are told apart: the kind and the server's key of the title (`movie|tmdb:603`). */
fun requestSlot(kind: String, key: String): String = "$kind|$key"

/** [KidRequest.key] of a movie or series (the server's naming, so a title page can find its request). */
fun tmdbRequestKey(tmdbId: Long): String = "tmdb:$tmdbId"

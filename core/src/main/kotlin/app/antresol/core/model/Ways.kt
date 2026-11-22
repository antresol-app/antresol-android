package app.antresol.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// The `x/app` protocol of the server's addons: what an addon offers to the apps as «ways» (to watch
// a title, to keep a copy on the server), the steps that lead to a choice, and how a chosen way runs.
// An addon is only ever an opaque id the server hands out; every label comes from the server too.

/** One entry of `GET /api/addons/web`: an addon open to the profile. Only [id] is used. */
@Serializable
data class AddonRef(val id: String = "")

/** `GET /api/addons/{id}/x/app`. */
@Serializable
data class AppInfo(
    val v: Int = 1,
    val ways: List<AddonWay> = emptyList(),
    /** The addon searches titles: the label of its results section; absent when it does not. */
    val search: WaySearch? = null,
)

/** Values of [AddonWay.role]. */
object WayRole {
    /** A way to watch: a choice of the «Watch» menu (or the first button when nothing else plays). */
    const val WATCH = "watch"

    /** An action «to the server»: a round button with a progress ring. */
    const val KEEP = "keep"
}

/** Values of [AddonWay.scope]. */
object WayScope {
    const val ANY = "any"

    /** Only for what is not in the library. */
    const val OUTSIDE = "outside"

    /** Only for what is in the library. */
    const val INSIDE = "inside"
}

/** Values of [AddonWay.icon]; anything else is shown as [PLAY]. */
object WayIcon {
    const val PLAY = "play"
    const val DOWNLOAD = "download"
    const val GLOBE = "globe"
    const val LIST = "list"
}

/**
 * A way an addon offers. [id] is opaque: the apps only hand it back. [addonId] is filled in by the
 * app when it collects the ways of every addon (it is not part of the server's answer).
 */
@Serializable
data class AddonWay(
    val id: String,
    val label: String = "",
    val icon: String = WayIcon.PLAY,
    val role: String = WayRole.WATCH,
    val kinds: List<String> = emptyList(),
    val scope: String = WayScope.ANY,
    /** Can be run for one episode (season and episode given). */
    val episodes: Boolean = false,
    /**
     * A short line under [label] in the menu of ways («Starts right away»), in the profile's language; an addon
     * that sends none gets a neutral line by its [icon] in the apps.
     */
    val hint: String? = null,
    @Transient val addonId: String = "",
) {
    val isKeep: Boolean get() = role == WayRole.KEEP

    /** Unique across addons: the key of this way in state maps and lists. */
    val key: String get() = "$addonId/$id"
}

/** The way of one addon to search titles. */
@Serializable
data class WaySearch(val label: String = "")

/** The place [label] shows a title search of [addonId] (one section after the library's results). */
data class AddonSearch(val addonId: String, val label: String)

/** `GET /api/addons/{id}/x/app/search?q=`. */
@Serializable
data class WaySearchResults(val titles: List<WayTitle> = emptyList())

/** A title an addon found: opens the library card ([libraryItemId]) or the page of a title outside it. */
@Serializable
data class WayTitle(
    val kind: String = "",
    val tmdbId: Long = 0,
    val title: String = "",
    val year: Int? = null,
    /** An absolute URL or a path of the server. */
    val poster: String? = null,
    val overview: String? = null,
    val inLibrary: Boolean = false,
    val libraryItemId: Long? = null,
)

/** Body of `POST x/app/step`. */
@Serializable
data class StepRequest(
    val way: String,
    val kind: String,
    val tmdbId: Long,
    val season: Int? = null,
    val episode: Int? = null,
    /** The `ref` of the row picked at the previous step; absent at the first step. */
    val ref: String? = null,
)

/** A step of a choice (`POST x/app/step`): a list of rows, all text from the server. */
@Serializable
data class WayStep(
    val title: String = "",
    val rows: List<StepRow> = emptyList(),
    /** Text above the list. */
    val note: String? = null,
    /** Text shown when [rows] is empty. */
    val empty: String? = null,
)

@Serializable
data class StepRow(
    val ref: String = "",
    val label: String = "",
    /** A second line. */
    val detail: String? = null,
    val badges: List<String> = emptyList(),
    /** The row picked last time. */
    val current: Boolean = false,
    /** Picking it runs the way with its [ref]; otherwise it opens the next step. */
    @SerialName("final") val isFinal: Boolean = false,
)

/** Body of `POST x/app/run`. */
@Serializable
data class RunRequest(
    val way: String,
    val kind: String,
    val tmdbId: Long,
    val season: Int? = null,
    val episode: Int? = null,
    val ref: String? = null,
    val startSec: Double? = null,
    val maxHeight: Int? = null,
    /** Without [ref]: let the addon pick (its remembered or best variant) and answer `pick` when it cannot. */
    val quick: Boolean? = null,
    /** The media link of an earlier run went stale (410): make a new one. */
    val refresh: Boolean? = null,
    /**
     * One of the names of [WayQuality.options] of an earlier `play` answer: with the [ref] that answer gave
     * ([WayPlay.ref]) the stream plays again in that quality.
     */
    val quality: String? = null,
)

/** The answer of `POST x/app/run`: exactly one of the fields is set. */
@Serializable
data class RunResult(
    /** Play through the core's input player (`/api/play/input/{id}`). */
    val input: String? = null,
    /** Play the stream itself. */
    val play: WayPlay? = null,
    /** Show this text; the state of the way changed. */
    val done: String? = null,
    /** The addon cannot decide: open the choice. */
    val pick: Boolean = false,
)

/** `play` of a run: the answer of `/api/play` for a stream the addon serves itself. */
@Serializable
data class WayPlay(
    /** Server path of the stream; the app adds `mk=`. */
    val url: String = "",
    val mode: String = "",
    /** `hls` or `mp4`. */
    val kind: String = "",
    val startSec: Double = 0.0,
    val durationMs: Long = 0,
    val subtitles: List<WaySubtitle> = emptyList(),
    val quality: WayQuality = WayQuality(),
    /** The addon's session: closed with `DELETE x/app/play/{session}`. */
    val session: String = "",
    val progress: WayProgress = WayProgress(),
    /**
     * The `ref` that plays this very stream again (a stale link, another of the [quality] options): sent with `run`.
     * Absent: the addon cannot play it in another quality, so none is offered.
     */
    val ref: String? = null,
) {
    val isPlaylist: Boolean get() = kind.equals(KIND_HLS, ignoreCase = true)

    /** The qualities to choose from: two or more, and a [ref] to play one with; empty otherwise. */
    val qualityChoice: List<String>
        get() = if (ref.isNullOrBlank()) emptyList() else quality.options.filter { it.isNotBlank() }.distinct().takeIf { it.size >= 2 }.orEmpty()

    companion object {
        const val KIND_HLS = "hls"
    }
}

@Serializable
data class WaySubtitle(
    val index: Int = 0,
    val lang: String = "",
    val label: String = "",
    val title: String = "",
    val codec: String = "",
    val external: Boolean = true,
    /** Server path of the WebVTT file; the app adds `mk=`. */
    val url: String = "",
)

@Serializable
data class WayQuality(val options: List<String> = emptyList(), val current: String = "")

/** Where and how often the position of a direct stream is reported. */
@Serializable
data class WayProgress(val path: String = "x/app/progress", val every: Int = 10)

/** Body of `PUT x/app/progress`. */
@Serializable
data class WayProgressBody(
    val session: String,
    val way: String,
    val kind: String,
    val tmdbId: Long,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean? = null,
)

/**
 * A quick run that is under way: which way, for which subject (the title, or one of its episodes). Only the
 * button of that way on that subject shows «Opening…»; another episode's button, or the title's, does not. One
 * run at a time: the controller refuses a second while this one is going.
 */
data class WayRun(
    /** [AddonWay.key]. */
    val way: String,
    val kind: String,
    val tmdbId: Long,
    val season: Int? = null,
    val episode: Int? = null,
) {
    /** The run is for this subject (any way): [season] and [episode] null for the title itself. */
    fun isFor(season: Int?, episode: Int?): Boolean = this.season == season && this.episode == episode

    /** The run is of [way] for this subject. */
    fun isFor(way: AddonWay, season: Int?, episode: Int?): Boolean = this.way == way.key && isFor(season, episode)
}

/** `GET x/app/state`: the state of the ways of one addon for one title. */
@Serializable
data class WayStateResponse(val ways: Map<String, WayState> = emptyMap())

@Serializable
data class WayState(
    val active: Boolean = false,
    /** 0..1, absent when unknown. */
    val fraction: Double? = null,
    /** «40% done»: the server's words. */
    val text: String? = null,
    /** The way can be cancelled: «Cancel» joins its menu. */
    val cancel: Boolean = false,
)

/** Body of `POST x/app/cancel`. */
@Serializable
data class WayCancelRequest(val way: String, val kind: String, val tmdbId: Long, val season: Int? = null)

/** `GET /api/play/input/{id}`: what an addon registered for the profile to play. */
@Serializable
data class InputInfo(
    val id: String = "",
    val addonId: String = "",
    val name: String = "",
    val itemId: Long = 0,
    val title: InputTitle? = null,
    val slow: Boolean = false,
    /** Where to start (the profile's saved position of the library item), seconds. */
    val startSec: Double = 0.0,
)

@Serializable
data class InputTitle(
    val kind: String = "",
    val tmdbId: Long = 0,
    val season: Int = 0,
    val episode: Int = 0,
)

/** Body of `PUT /api/play/input/{id}/progress`. */
@Serializable
data class InputProgressBody(val positionMs: Long, val durationMs: Long, val keepalive: Boolean = false)

/**
 * `GET /api/title/{kind}/{tmdbId}`: the page of a movie or series that may not be in the library.
 * [inLibrary] says whether the library has a copy, [libraryItemId] which.
 */
@Serializable
data class TitleInfo(
    val tmdbId: Long = 0,
    val kind: String = "",
    val title: String = "",
    val year: Int = 0,
    val poster: String = "",
    val overview: String = "",
    val inLibrary: Boolean = false,
    val libraryItemId: Long? = null,
    val originalTitle: String? = null,
    val backdrop: String = "",
    val genres: List<String> = emptyList(),
    /** Minutes. */
    val runtime: Int? = null,
    val rating: String? = null,
    val cast: List<CastMember> = emptyList(),
    /** A series' regular seasons with their episode counts. */
    val seasons: List<SeasonCount> = emptyList(),
) {
    /** The title as a library item with id 0, so the detail pages draw it like any other. */
    fun asItem(): ItemDetail = ItemDetail(
        id = 0,
        kind = kind,
        title = title,
        year = year.takeIf { it > 0 },
        poster = poster,
        backdrop = backdrop,
        originalTitle = originalTitle,
        overview = overview,
        genres = genres,
        runtime = runtime?.takeIf { it > 0 },
        rating = rating,
        cast = cast,
        tmdbId = tmdbId,
    )
}

@Serializable
data class SeasonCount(val number: Int = 0, val episodes: Int = 0)

/** The answer of `POST /api/play/input/{id}`. */
sealed interface InputPlay {
    /** 202: the server is still probing the video; ask again. */
    data object Pending : InputPlay

    data class Ready(val play: PlayResponse) : InputPlay
}

/** A title an addon found, as a card of a results row: the library item when it is there, else a title off the library. */
fun WayTitle.toCard(): Card = Card(
    id = if (inLibrary) libraryItemId ?: 0 else 0,
    kind = kind,
    title = title,
    year = year,
    poster = poster.orEmpty(),
    onServer = inLibrary,
    tmdbId = tmdbId,
)

/** A movie or series by its TMDB id: what the page of a title off the library is opened by. */
data class TitleRef(val kind: String, val tmdbId: Long)

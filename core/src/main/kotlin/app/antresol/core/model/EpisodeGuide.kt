package app.antresol.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/tracking/{series|movie}/{tmdbId}/episodes` (server 0.5.0+): every episode TMDB knows
 * (plus library files TMDB lacks), with the profile's watched state and the sources it may use.
 */
@Serializable
data class EpisodeGuideResponse(
    val title: GuideTitle = GuideTitle(),
    val episodes: List<GuideEntry> = emptyList(),
)

@Serializable
data class GuideTitle(
    val kind: String = "",
    val tmdbId: Long = 0,
    val name: String = "",
    val poster: String = "",
    /** TMDB status ("Returning Series", "Ended", …). */
    val status: String = "",
    /** The library item of the title, when it is in the library. */
    val itemId: Long? = null,
)

@Serializable
data class GuideEntry(
    val season: Int = 0,
    val number: Int = 0,
    val name: String = "",
    /** `YYYY-MM-DD`; empty when unknown (not announced, or a library file TMDB does not list). */
    val airDate: String = "",
    val watched: Boolean = false,
    val positionSec: Int? = null,
    /** Unwatched news by the feed's rule (has a source other than the library's). */
    val new: Boolean = false,
    val sources: List<GuideSource> = emptyList(),
) {
    /** The library's copy of the episode, if any. */
    val librarySource: GuideSource? get() = sources.firstOrNull { it.kind == GuideSource.KIND_LIBRARY }
}

@Serializable
data class GuideSource(
    /** `library`, `pinned`, `torrent` or `online`; the apps play only `library`. */
    val kind: String = "",
    val ref: String = "",
    val provider: String? = null,
    val quality: String? = null,
    val labels: List<String> = emptyList(),
    val firstSeen: String = "",
    val meta: GuideSourceMeta = GuideSourceMeta(),
) {
    companion object {
        const val KIND_LIBRARY = "library"
    }
}

@Serializable
data class GuideSourceMeta(
    /** Library: the episode item to play (`/api/items/{itemId}`). */
    val itemId: Long? = null,
    /** Library: the file. */
    val fileId: Long? = null,
    val sourceId: String? = null,
    val providerId: String? = null,
    val voice: String? = null,
    val lang: String? = null,
)

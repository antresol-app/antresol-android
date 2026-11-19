package app.antresol.tv.homechannels

/** Where a program is shown on the TV's home screen. */
enum class Slot {
    /** The system's Watch Next row (Google TV: «Продолжить просмотр»). */
    WatchNext,

    /** The app's own «Antresol» preview channel. */
    Channel,
}

enum class ProgramKind { Movie, Episode, Series }

/** What Watch Next says about a program. */
enum class WatchNextKind {
    /** Stopped in the middle: shows a progress bar. */
    Continue,

    /** The next unwatched episode (nothing played of it yet). */
    Next,
}

/** The shape of the tile's picture. A row looks best when all its pictures have one shape. */
enum class Aspect { Wide, Poster }

/** Server-relative image paths: [path] first, [fallbackPath] when it cannot be fetched. `""` = none. */
data class ArtworkRef(val path: String, val fallbackPath: String = "")

/**
 * One tile of the home screen, apart from Android: what [HomeMapper] makes of the server's answer and what
 * [HomeDiff] compares with what the system already holds.
 *
 * [key] is stable per profile and title: it is stored as the program's internal provider id, so a program is
 * found again on the next run. [rank] is the place in the list (0 = first). [posterUri] is the picture as the
 * launcher can read it; it is filled in after the artwork is cached, and is part of [fingerprint].
 */
data class ProgramSpec(
    val key: String,
    val slot: Slot,
    val kind: ProgramKind,
    val title: String,
    val artwork: ArtworkRef,
    val aspect: Aspect,
    val link: HomeLink,
    val rank: Int,
    val watchNext: WatchNextKind? = null,
    val description: String = "",
    val episodeTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val posterUri: String? = null,
) {
    /**
     * A 64-bit digest of everything that is shown or acted on, kept in the program's first internal flag. A
     * program whose digest has not changed is left alone. Computed from text, not from `hashCode`, because enum
     * hash codes differ from one process to the next.
     */
    fun fingerprint(): Long {
        val parts = listOf(
            key, slot, kind, watchNext, title, description, episodeTitle, season, episode, year,
            positionMs, durationMs, aspect, posterUri, HomeLinks.uri(link), rank,
        )
        var hash = FNV_OFFSET
        for (byte in parts.joinToString("\u0001") { it?.toString() ?: "\u0000" }.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toLong() and 0xff)) * FNV_PRIME
        }
        return hash
    }

    private companion object {
        const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
        const val FNV_PRIME = 1099511628211L
    }
}

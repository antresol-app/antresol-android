package app.antresol.core.model

import kotlinx.serialization.Serializable

/** Item kinds as they appear in [Card.kind]. */
object Kind {
    const val MOVIE = "movie"
    const val SERIES = "series"
    const val SEASON = "season"
    const val EPISODE = "episode"
    const val ARTIST = "artist"
    const val ALBUM = "album"
    const val PLAYLIST = "playlist"
    const val AUDIOBOOK = "audiobook"
    const val BOOK = "book"
    const val COMIC = "comic"
    const val COMIC_VOLUME = "comic_volume"
}

/**
 * Fields shared by every card-shaped object. The server flattens these into item detail,
 * episode cards and comic volume cards, so those types implement this interface too.
 */
interface CardFields {
    val id: Long
    val kind: String
    val title: String
    val subtitle: String
    val year: Int?
    val poster: String
    val backdrop: String
    val logo: String
    val progress: Double
    val badges: List<String>
    val seriesId: Long?

    fun toCard(): Card = Card(
        id = id, kind = kind, title = title, subtitle = subtitle, year = year,
        poster = poster, backdrop = backdrop, logo = logo, progress = progress,
        badges = badges, seriesId = seriesId,
    )
}

/** The Card object (spec section 3.1). Image fields are server-relative paths or `""`. */
@Serializable
data class Card(
    override val id: Long,
    override val kind: String = "",
    override val title: String = "",
    override val subtitle: String = "",
    override val year: Int? = null,
    override val poster: String = "",
    override val backdrop: String = "",
    override val logo: String = "",
    override val progress: Double = 0.0,
    override val badges: List<String> = emptyList(),
    val onServer: Boolean = true,
    override val seriesId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val resumeSec: Double? = null,
) : CardFields {
    override fun toCard(): Card = this
}

@Serializable
data class Shelf(
    val id: String,
    val title: String = "",
    val cards: List<Card> = emptyList(),
)

/** Shelf ids of `GET /api/home`, `GET /api/music/home` and `GET /api/books/home`. */
object ShelfId {
    const val CONTINUE = "continue"
    const val RECENTLY_PLAYED = "recently-played"
    const val READING = "reading"
    const val RECENT_MOVIES = "recent-movies"
    const val RECENT_SERIES = "recent-series"
    const val WATCHLIST = "watchlist"
    const val RECENT_ALBUMS = "recent-albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val WANT = "want"
    const val AUDIOBOOKS = "audiobooks"
    const val BOOKS = "books"
    const val COMICS = "comics"
    const val DONE = "done"
}

@Serializable
data class HomeResponse(
    val hero: List<Card> = emptyList(),
    val shelves: List<Shelf> = emptyList(),
)

/** Response of shelf-only home screens (`/api/music/home`, `/api/books/home`). */
@Serializable
data class ShelvesResponse(val shelves: List<Shelf> = emptyList())

/** A page of a list with `total`, used for items, albums, artists, songs and books. */
@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
) {
    fun hasMore(offset: Int): Boolean = offset + items.size < total
}

/** `GET /api/search`. */
@Serializable
data class SearchResults(
    val library: List<Card> = emptyList(),
    val albums: List<Card> = emptyList(),
    val artists: List<Card> = emptyList(),
    val songs: List<Track> = emptyList(),
    val audiobooks: List<Card> = emptyList(),
    val books: List<Card> = emptyList(),
    val comics: List<Card> = emptyList(),
) {
    val isEmpty: Boolean
        get() = library.isEmpty() && albums.isEmpty() && artists.isEmpty() && songs.isEmpty() &&
            audiobooks.isEmpty() && books.isEmpty() && comics.isEmpty()
}

/** `GET /api/library/status`. Counts are library totals. */
@Serializable
data class LibraryStatus(
    val running: Boolean = false,
    val queued: Boolean = false,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val error: String? = null,
    val movies: Int = 0,
    val episodes: Int = 0,
    val albums: Int = 0,
    val tracks: Int = 0,
    val books: Int = 0,
    val audiobooks: Int = 0,
    val comics: Int = 0,
    val files: Int = 0,
    val skipped: Int = 0,
    val removed: Int = 0,
)

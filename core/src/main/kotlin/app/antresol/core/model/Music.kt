package app.antresol.core.model

import kotlinx.serialization.Serializable

/** The Track object (spec section 7.1). [id] is the track item id used for streaming, lyrics, plays and star. */
@Serializable
data class Track(
    val id: Long,
    val title: String = "",
    val artist: String = "",
    val artistId: Long = 0,
    val albumId: Long = 0,
    val album: String = "",
    val discNo: Int = 0,
    val trackNo: Int = 0,
    val durationMs: Long = 0,
    val fileId: Long = 0,
    val lossless: Boolean = false,
    val hasLyrics: Boolean = false,
    val cover: String = "",
    val starred: Boolean = false,
    val cue: Boolean = false,
    /** Position within a playlist; only in playlist responses. */
    val idx: Int? = null,
)

@Serializable
data class ArtistRef(
    val id: Long,
    val name: String = "",
)

/** `GET /api/music/albums/{id}`. */
@Serializable
data class AlbumDetail(
    val card: Card,
    val artist: ArtistRef? = null,
    val year: Int = 0,
    val genres: List<String> = emptyList(),
    val durationMs: Long = 0,
    val trackCount: Int = 0,
    val quality: String = "",
    val tracks: List<Track> = emptyList(),
    val starred: Boolean = false,
)

/** `GET /api/music/artists/{id}`. */
@Serializable
data class ArtistDetail(
    val card: Card,
    val albums: List<Card> = emptyList(),
    val topTracks: List<Track> = emptyList(),
    val appearsOn: List<Card> = emptyList(),
)

@Serializable
data class Genre(
    val name: String,
    val albums: Int = 0,
    val songs: Int = 0,
)

@Serializable
data class GenresResponse(val genres: List<Genre> = emptyList())

@Serializable
data class TrackList(val tracks: List<Track> = emptyList())

@Serializable
data class LyricLine(
    /** Seconds. */
    val t: Double,
    val text: String = "",
)

@Serializable
data class Lyrics(
    val lyrics: String = "",
    val synced: Boolean = false,
    val source: String = "",
    val lines: List<LyricLine> = emptyList(),
)

@Serializable
data class Playlist(
    val id: Long,
    val name: String = "",
    val trackCount: Int = 0,
    val durationMs: Long = 0,
    val cover: String = "",
    /** Up to 4 album covers for a mosaic. */
    val covers: List<String> = emptyList(),
    val updatedAt: String = "",
)

@Serializable
data class PlaylistList(val items: List<Playlist> = emptyList())

@Serializable
data class PlaylistDetail(
    val playlist: Playlist,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class PlaylistCreate(
    val name: String,
    val trackIds: List<Long>? = null,
)

/**
 * Body of `PATCH /api/music/playlists/{id}`. [remove] and [order] hold positions;
 * `order[i]` is the old position placed at new position `i`.
 */
@Serializable
data class PlaylistPatch(
    val name: String? = null,
    val append: List<Long>? = null,
    val remove: List<Int>? = null,
    val order: List<Int>? = null,
)

@Serializable
internal data class PlayRecord(val trackId: Long, val at: String? = null)

/** Sort orders of `GET /api/music/albums`. */
object AlbumSort {
    const val ADDED = "added"
    const val TITLE = "title"
    const val ARTIST = "artist"
    const val YEAR = "year"
    const val RANDOM = "random"
    const val FREQUENT = "frequent"
    const val RECENT = "recent"
}

/** Source of `GET /api/music/shuffle`. */
sealed interface ShuffleSource {
    data object All : ShuffleSource
    data class Genre(val name: String) : ShuffleSource
    data class Artist(val artistId: Long) : ShuffleSource
}

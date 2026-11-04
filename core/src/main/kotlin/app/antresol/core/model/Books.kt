package app.antresol.core.model

import kotlinx.serialization.Serializable

/** Kinds accepted by `GET /api/books?kind=`. */
object BookKind {
    const val BOOK = "book"
    const val AUDIOBOOK = "audiobook"
    const val COMIC = "comic"
}

/** Sort orders of `GET /api/books`. */
object BookSort {
    const val ADDED = "added"
    const val TITLE = "title"
    const val AUTHOR = "author"
}

/** `GET /api/books/{id}` for book, audiobook, comic series and comic volume ids. */
@Serializable
data class BookDetail(
    val card: Card,
    val kind: String = "",
    val authors: List<String> = emptyList(),
    val narrator: String = "",
    val series: BookSeries? = null,
    val description: String = "",
    val year: Int = 0,
    val lang: String = "",
    /** For an audiobook: ordered parts. Empty for a comic series. */
    val files: List<BookFile> = emptyList(),
    val seriesId: Long = 0,
    val nextId: Long = 0,
    /** File id to open in a reader (ebook or comic volume), 0 otherwise. */
    val readFileId: Long = 0,
    val progress: BookProgress? = null,
    val want: Boolean = false,
    /** Audiobook total: the sum of the parts. */
    val durationMs: Long = 0,
    /** Audiobook chapters on the whole-book timeline. */
    val chapters: List<Chapter> = emptyList(),
    val volumes: List<VolumeCard> = emptyList(),
    /** `"rtl"`, `"ltr"` or `""` for non-comics. */
    val direction: String = "",
)

@Serializable
data class BookSeries(
    val name: String = "",
    val index: Double = 0.0,
)

@Serializable
data class BookFile(
    val id: Long,
    val format: String = "",
    val size: Long = 0,
    val durationMs: Long = 0,
)

@Serializable
data class BookProgress(
    val fraction: Double = 0.0,
    val locator: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val finished: Boolean = false,
)

@Serializable
data class Chapter(
    val idx: Int,
    val title: String = "",
    val fileId: Long = 0,
    val startMs: Long = 0,
    val endMs: Long = 0,
)

/** A comic volume inside [BookDetail.volumes]: card fields plus reader info. */
@Serializable
data class VolumeCard(
    override val id: Long,
    override val kind: String = Kind.COMIC_VOLUME,
    override val title: String = "",
    override val subtitle: String = "",
    override val year: Int? = null,
    override val poster: String = "",
    override val backdrop: String = "",
    override val logo: String = "",
    override val progress: Double = 0.0,
    override val badges: List<String> = emptyList(),
    override val seriesId: Long? = null,
    val pages: Int = 0,
    val direction: String = "",
    val fileId: Long = 0,
) : CardFields

/** `GET /api/comics/{fileId}/pages`. [sizes] holds `[width, height]` per page. */
@Serializable
data class ComicPages(
    val count: Int = 0,
    val direction: String = "",
    val sizes: List<List<Int>> = emptyList(),
) {
    val isRightToLeft: Boolean get() = direction == "rtl"
}

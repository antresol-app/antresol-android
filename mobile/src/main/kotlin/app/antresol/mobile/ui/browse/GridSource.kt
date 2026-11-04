package app.antresol.mobile.ui.browse

import androidx.annotation.StringRes
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.AlbumSort
import app.antresol.core.model.BookSort
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.Page
import app.antresol.core.model.Shelf
import app.antresol.core.model.ShelfId
import app.antresol.mobile.R
import app.antresol.mobile.ui.components.CardStyle

/** The sort pills of grid pages. Each source maps them onto its endpoint's values. */
enum class GridSort(@StringRes val label: Int) {
    Recent(R.string.sort_new),
    Title(R.string.sort_az),
    Year(R.string.sort_by_year),
    Artist(R.string.sort_by_artist),
    Author(R.string.sort_by_author),
}

/** Pages with shelves whose cards can be listed on their own ("See all" of Continue Watching). */
enum class ShelfPage {
    Home, Music, Books;

    suspend fun shelves(client: AntresolClient): List<Shelf> = when (this) {
        Home -> client.home().shelves
        Music -> client.musicHome().shelves
        Books -> client.booksHome().shelves
    }
}

/** What a grid page lists. Encoded into [app.antresol.mobile.ui.shell.GridRoute] as a short string. */
sealed interface GridSource {
    /** Sort pills to offer, the first one is the default; empty = no pills. */
    val sorts: List<GridSort> get() = emptyList()

    /** Fixed artwork frame for every card, or null to frame each card by its kind. */
    val style: CardStyle? get() = null

    suspend fun page(client: AntresolClient, sort: GridSort?, offset: Int): Page<Card>

    fun encode(): String

    /** Movies (`movie`) or TV shows (`series`) from `/api/items`. */
    data class Items(val kind: String) : GridSource {
        override val sorts get() = listOf(GridSort.Recent, GridSort.Title, GridSort.Year)

        override suspend fun page(client: AntresolClient, sort: GridSort?, offset: Int) =
            client.items(kind = kind, sort = when (sort) {
                GridSort.Title -> "title"
                GridSort.Year -> "year"
                else -> "recent"
            }, offset = offset)

        override fun encode() = "items|$kind"
    }

    /** Albums, optionally of one genre. */
    data class Albums(val genre: String? = null) : GridSource {
        override val sorts get() = listOf(GridSort.Recent, GridSort.Title, GridSort.Artist, GridSort.Year)
        override val style get() = CardStyle.Square

        override suspend fun page(client: AntresolClient, sort: GridSort?, offset: Int) =
            client.albums(sort = when (sort) {
                GridSort.Title -> AlbumSort.TITLE
                GridSort.Artist -> AlbumSort.ARTIST
                GridSort.Year -> AlbumSort.YEAR
                else -> AlbumSort.ADDED
            }, genre = genre, offset = offset)

        override fun encode() = "albums|${genre.orEmpty()}"
    }

    data object Artists : GridSource {
        override val style get() = CardStyle.Circle
        override suspend fun page(client: AntresolClient, sort: GridSort?, offset: Int) = client.artists(offset = offset)
        override fun encode() = "artists"
    }

    data object Playlists : GridSource {
        override val style get() = CardStyle.Square

        override suspend fun page(client: AntresolClient, sort: GridSort?, offset: Int): Page<Card> {
            val cards = client.playlists().items.map { playlist ->
                Card(id = playlist.id, kind = Kind.PLAYLIST, title = playlist.name, poster = playlist.cover)
            }
            return Page(cards, cards.size)
        }

        override fun encode() = "playlists"
    }

    /** Books, audiobooks or comic series (`kind` of `/api/books`). */
    data class Books(val kind: String) : GridSource {
        override val sorts get() = listOf(GridSort.Recent, GridSort.Title, GridSort.Author)

        override suspend fun page(client: AntresolClient, sort: GridSort?, offset: Int) =
            client.books(kind = kind, sort = when (sort) {
                GridSort.Title -> BookSort.TITLE
                GridSort.Author -> BookSort.AUTHOR
                else -> BookSort.ADDED
            }, offset = offset)

        override fun encode() = "books|$kind"
    }

    /** Every card of one shelf of a shelf page (the server sends shelves whole). */
    data class ShelfCards(val page: ShelfPage, val shelfId: String) : GridSource {
        override val style get() = if (shelfId == ShelfId.CONTINUE) CardStyle.Wide else null

        override suspend fun page(client: AntresolClient, sort: GridSort?, offset: Int): Page<Card> {
            val cards = page.shelves(client).firstOrNull { it.id == shelfId }?.cards.orEmpty()
            return Page(cards, cards.size)
        }

        override fun encode() = "shelf|${page.name}|$shelfId"
    }

    companion object {
        fun decode(value: String): GridSource {
            val type = value.substringBefore('|')
            val rest = value.substringAfter('|', "")
            return when (type) {
                "items" -> Items(rest)
                "albums" -> Albums(rest.ifBlank { null })
                "artists" -> Artists
                "playlists" -> Playlists
                "books" -> Books(rest)
                "shelf" -> ShelfCards(ShelfPage.valueOf(rest.substringBefore('|')), rest.substringAfter('|'))
                else -> throw IllegalArgumentException("Unknown grid source $value")
            }
        }

        /**
         * Where a shelf's chevron leads: the matching full library list when there is one,
         * otherwise the shelf's own cards.
         */
        fun forShelf(page: ShelfPage, shelfId: String): GridSource = when (shelfId) {
            ShelfId.RECENT_MOVIES -> Items(Kind.MOVIE)
            ShelfId.RECENT_SERIES -> Items(Kind.SERIES)
            ShelfId.RECENT_ALBUMS -> Albums()
            ShelfId.ARTISTS -> Artists
            ShelfId.PLAYLISTS -> Playlists
            ShelfId.AUDIOBOOKS -> Books(Kind.AUDIOBOOK)
            ShelfId.BOOKS -> Books(Kind.BOOK)
            ShelfId.COMICS -> Books(Kind.COMIC)
            else -> ShelfCards(page, shelfId)
        }
    }
}

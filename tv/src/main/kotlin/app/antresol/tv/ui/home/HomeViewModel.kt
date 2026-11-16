package app.antresol.tv.ui.home

import app.antresol.core.api.AntresolClient
import app.antresol.core.playback.PendingWrites
import app.antresol.core.model.Card
import app.antresol.core.model.HomeResponse
import app.antresol.core.model.Kind
import app.antresol.core.model.Shelf
import app.antresol.core.model.ShelfId
import app.antresol.ui.LoadViewModel

/**
 * What the home screen shows: the hero rotation and the non-empty `/api/home` shelves in server
 * order, without books (the TV app has none, as on Apple TV).
 */
data class HomeContent(
    val hero: List<Card>,
    /** Title of the shelf the hero was borrowed from when the server sent no hero; null = "New in Library". */
    val heroSource: String?,
    val shelves: List<Shelf>,
) {
    val isEmpty: Boolean get() = hero.isEmpty() && shelves.isEmpty()

    companion object {
        private const val MAX_HERO = 6
        private val VIDEO_KINDS = setOf(Kind.MOVIE, Kind.SERIES, Kind.EPISODE)
        private val HERO_FALLBACK_SHELVES = listOf(ShelfId.CONTINUE, ShelfId.RECENT_MOVIES, ShelfId.RECENT_SERIES, ShelfId.WATCHLIST)

        fun from(home: HomeResponse): HomeContent {
            val response = home.withoutBooks()
            val shelves = response.shelves.filter { it.cards.isNotEmpty() }
            if (response.hero.isNotEmpty()) return HomeContent(response.hero.take(MAX_HERO), null, shelves)
            val fallback = HERO_FALLBACK_SHELVES.firstNotNullOfOrNull { id ->
                shelves.firstOrNull { it.id == id }?.let { shelf ->
                    shelf.cards.firstOrNull { it.kind in VIDEO_KINDS }?.let { shelf to it }
                }
            }
            return HomeContent(listOfNotNull(fallback?.second), fallback?.first?.title, shelves)
        }
    }
}

/**
 * Loads Home. It refreshes in place whenever the screen comes back (from the player, a detail
 * page) and when a final progress write lands, so Continue Watching reflects what was just played;
 * failures keep the shown content.
 */
class HomeViewModel(client: AntresolClient, pendingWrites: PendingWrites) :
    LoadViewModel<HomeContent>(staleAfterMs = 0, pendingWrites = pendingWrites, fetch = { HomeContent.from(client.home()) })

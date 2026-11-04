package app.antresol.mobile.ui.home

import app.antresol.core.model.Card
import app.antresol.core.model.HomeResponse
import app.antresol.core.model.Kind
import app.antresol.core.model.Shelf
import app.antresol.core.model.ShelfId

/** What the home screen shows: the hero rotation and the non-empty `/api/home` shelves in server order. */
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

        fun from(response: HomeResponse): HomeContent {
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

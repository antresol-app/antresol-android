package app.antresol.tv.ui.shell

import androidx.annotation.StringRes
import app.antresol.core.model.Card
import app.antresol.ui.R
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object MoviesRoute

@Serializable
data object ShowsRoute

@Serializable
data object MusicRoute

@Serializable
data object BooksRoute

@Serializable
data object SearchRoute

@Serializable
data object AccountRoute

/** Any card's detail page; [kind] is [Card.kind]. */
@Serializable
data class DetailRoute(val kind: String, val id: Long) {
    constructor(card: Card) : this(card.kind, card.id)
}

/** The top navigation pills, in display order. The search pill shows only its icon ([label] is its description). */
enum class NavTab(val route: Any, @StringRes val label: Int, val iconOnly: Boolean = false) {
    Search(SearchRoute, R.string.nav_search, iconOnly = true),
    Home(HomeRoute, R.string.nav_home),
    Movies(MoviesRoute, R.string.nav_movies),
    Shows(ShowsRoute, R.string.nav_tv_shows),
    Music(MusicRoute, R.string.nav_music),
    Books(BooksRoute, R.string.nav_books),
}

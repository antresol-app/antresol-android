package app.antresol.mobile.ui.shell

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import app.antresol.core.model.Card
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
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

/** Any card's detail page; [kind] is [Card.kind]. */
@Serializable
data class DetailRoute(val kind: String, val id: Long) {
    constructor(card: Card) : this(card.kind, card.id)
}

/**
 * A pushed grid page ("All Albums", "Audiobooks", a genre, a home shelf). [source] is a
 * [app.antresol.mobile.ui.browse.GridSource] encoded with [app.antresol.mobile.ui.browse.GridSource.encode].
 */
@Serializable
data class GridRoute(val source: String, val title: String)

/** The bottom bar tabs in display order; Search sits in its own round button next to the pill. */
enum class NavTab(val route: Any, @StringRes val label: Int, val icon: ImageVector) {
    Home(HomeRoute, R.string.nav_home, MobileIcons.Home),
    Movies(MoviesRoute, R.string.nav_movies, MobileIcons.Film),
    Shows(ShowsRoute, R.string.nav_tv_shows, MobileIcons.Tv),
    Music(MusicRoute, R.string.nav_music, MobileIcons.Music),
    Books(BooksRoute, R.string.nav_books, MobileIcons.Book),
    Search(SearchRoute, R.string.nav_search, AntresolIcons.Search),
}

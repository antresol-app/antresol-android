package app.antresol.tv.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.account.AccountScreen
import app.antresol.tv.ui.browse.LibraryGridScreen
import app.antresol.tv.ui.browse.LibraryGridViewModel
import app.antresol.tv.ui.browse.ShelvesScreen
import app.antresol.tv.ui.browse.ShelvesViewModel
import app.antresol.tv.ui.detail.DetailScreen
import app.antresol.tv.ui.detail.DetailViewModel
import app.antresol.tv.ui.home.HomeScreen
import app.antresol.tv.ui.home.HomeViewModel
import app.antresol.tv.ui.search.SearchScreen
import app.antresol.tv.ui.search.SearchViewModel
import app.antresol.ui.R
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import kotlinx.coroutines.launch

/**
 * The signed-in app: top pill navigation over a NavHost of tab screens and detail pages.
 * The navigation hides while a tab's content is scrolled down and comes back when it gets focus.
 */
@Composable
fun MainShell(session: SessionManager, state: AuthState.SignedIn) {
    val client = session.requireClient()
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val selectedTab = NavTab.entries.firstOrNull { tab ->
        backStack?.destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
    }
    val onAccount = backStack?.destination?.hasRoute(AccountRoute::class) == true
    var contentAtTop by remember { mutableStateOf(true) }
    var navFocused by remember { mutableStateOf(false) }
    val showNav = (selectedTab != null || onAccount) && (navFocused || contentAtTop)
    val navAlpha by animateFloatAsState(if (showNav) 1f else 0f, tween(AntresolMotion.MEDIUM_MS), label = "nav-alpha")

    val open: (Card) -> Unit = { card -> navController.navigate(DetailRoute(card)) }
    val canTakeFocus = { !navFocused }
    val onScrolledToTop: (Boolean) -> Unit = { contentAtTop = it }

    CompositionLocalProvider(LocalMediaUrls provides client.urls) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AntresolColors.Background),
        ) {
            NavHost(navController = navController, startDestination = HomeRoute, modifier = Modifier.fillMaxSize()) {
                composable<HomeRoute> {
                    HomeScreen(
                        viewModel = viewModel { HomeViewModel(client) },
                        onOpen = open,
                        // The player arrives with the next milestone; until then Play opens the detail page.
                        onPlay = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                    )
                }
                composable<MoviesRoute> {
                    LibraryGridScreen(
                        title = R.string.nav_movies,
                        viewModel = viewModel { LibraryGridViewModel(client, Kind.MOVIE) },
                        onOpen = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                    )
                }
                composable<ShowsRoute> {
                    LibraryGridScreen(
                        title = R.string.nav_tv_shows,
                        viewModel = viewModel { LibraryGridViewModel(client, Kind.SERIES) },
                        onOpen = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                    )
                }
                composable<MusicRoute> {
                    ShelvesScreen(
                        title = R.string.nav_music,
                        viewModel = viewModel { ShelvesViewModel { client.musicHome().shelves } },
                        onOpen = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                    )
                }
                composable<BooksRoute> {
                    ShelvesScreen(
                        title = R.string.nav_books,
                        viewModel = viewModel { ShelvesViewModel { client.booksHome().shelves } },
                        onOpen = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                    )
                }
                composable<SearchRoute> {
                    SearchScreen(
                        viewModel = viewModel { SearchViewModel(client) },
                        onOpen = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                    )
                }
                composable<AccountRoute> {
                    AccountScreen(
                        state = state,
                        serverUrl = client.baseUrl,
                        onSwitchProfile = { scope.launch { session.switchProfile() } },
                        onSignOut = { scope.launch { session.signOut() } },
                        onChangeServer = { scope.launch { session.changeServer() } },
                        onScrolledToTop = onScrolledToTop,
                    )
                }
                composable<DetailRoute> { entry ->
                    val route = entry.toRoute<DetailRoute>()
                    DetailScreen(
                        viewModel = viewModel { DetailViewModel(client, route.kind, route.id) },
                        onOpen = open,
                    )
                }
            }
            if (selectedTab != null || onAccount) {
                // A soft scrim keeps the pills readable over scrolled content.
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = if (contentAtTop) 0f else navAlpha }
                        .background(Brush.verticalGradient(0f to AntresolColors.Background.copy(alpha = 0.85f), 0.16f to Color.Transparent)),
                )
                TopNav(
                    selected = selectedTab,
                    userName = state.session.userName,
                    avatar = state.session.avatar,
                    onSelect = { tab -> navController.navigateToTab(tab) },
                    onAccount = { navController.navigate(AccountRoute) { launchSingleTop = true } },
                    onFocusChanged = { navFocused = it },
                    modifier = Modifier.graphicsLayer { alpha = navAlpha },
                )
            }
        }
    }
}

/** Switches tabs the TV way: one entry per tab, each keeping its own scroll and focus state. */
private fun NavHostController.navigateToTab(tab: NavTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

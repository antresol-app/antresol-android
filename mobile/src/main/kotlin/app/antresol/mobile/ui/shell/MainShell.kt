package app.antresol.mobile.ui.shell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
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
import app.antresol.mobile.ui.LoadViewModel
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.browse.BooksScreen
import app.antresol.mobile.ui.browse.CardGridScreen
import app.antresol.mobile.ui.browse.CardGridViewModel
import app.antresol.mobile.ui.browse.GridSource
import app.antresol.mobile.ui.browse.MusicHome
import app.antresol.mobile.ui.browse.MusicScreen
import app.antresol.mobile.ui.browse.OpenGrid
import app.antresol.mobile.ui.browse.ShelfPage
import app.antresol.mobile.ui.components.HeaderProfile
import app.antresol.mobile.ui.components.LocalHeaderProfile
import app.antresol.mobile.ui.detail.ItemDetailViewModel
import app.antresol.mobile.ui.detail.MediaDetailScreen
import app.antresol.mobile.ui.detail.MediaDetailViewModel
import app.antresol.mobile.ui.detail.VideoDetailScreen
import app.antresol.mobile.ui.home.HomeContent
import app.antresol.mobile.ui.home.HomeScreen
import app.antresol.mobile.ui.search.SearchScreen
import app.antresol.mobile.ui.search.SearchViewModel
import app.antresol.ui.R
import app.antresol.ui.theme.AntresolColors
import kotlinx.coroutines.launch

private val VIDEO_KINDS = setOf(Kind.MOVIE, Kind.SERIES, Kind.SEASON, Kind.EPISODE)

/**
 * The signed-in app: a NavHost of tab roots and pushed pages under the floating tab bar. Each tab
 * keeps its own back stack (save/restore state); tapping the current tab pops back to its root.
 * The tab header's avatar opens the account sheet.
 */
@Composable
fun MainShell(session: SessionManager, state: AuthState.SignedIn) {
    val client = session.requireClient()
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var currentTab by rememberSaveable { mutableStateOf(NavTab.Home) }

    val backStack by navController.currentBackStackEntryAsState()
    LaunchedEffect(backStack) {
        val destination = backStack?.destination ?: return@LaunchedEffect
        NavTab.entries.firstOrNull { destination.hasRoute(it.route::class) }?.let { currentTab = it }
    }

    val open: (Card) -> Unit = { card -> navController.navigate(DetailRoute(card)) }
    val openGrid: OpenGrid = { source, title -> navController.navigate(GridRoute(source.encode(), title)) }
    val selectTab: (NavTab) -> Unit = { tab -> navController.selectTab(tab, reselect = tab == currentTab) }
    val back: () -> Unit = { navController.popBackStack() }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val user = state.user
    CompositionLocalProvider(
        LocalMediaUrls provides client.urls,
        LocalBottomBarPadding provides PaddingValues(bottom = navBottom + BottomBarSpace),
        LocalHeaderProfile provides HeaderProfile(
            name = user?.name ?: state.session.userName,
            avatar = user?.avatar ?: state.session.avatar,
            onClick = { showAccount = true },
        ),
    ) {
        Box(Modifier.fillMaxSize().background(AntresolColors.Background)) {
            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = { fadeOut(tween(200)) },
            ) {
                composable<HomeRoute> {
                    HomeScreen(
                        viewModel = viewModel { LoadViewModel { HomeContent.from(client.home()) } },
                        onOpen = open,
                        // Playback arrives with the next milestone; until then Play opens the detail page.
                        onPlay = open,
                        onSeeAll = { shelf -> openGrid(GridSource.forShelf(ShelfPage.Home, shelf.id), shelf.title) },
                    )
                }
                composable<MoviesRoute> {
                    CardGridScreen(
                        title = context.getString(R.string.nav_movies),
                        viewModel = viewModel { CardGridViewModel(client, GridSource.Items(Kind.MOVIE)) },
                        onOpen = open,
                    )
                }
                composable<ShowsRoute> {
                    CardGridScreen(
                        title = context.getString(R.string.nav_tv_shows),
                        viewModel = viewModel { CardGridViewModel(client, GridSource.Items(Kind.SERIES)) },
                        onOpen = open,
                    )
                }
                composable<MusicRoute> {
                    MusicScreen(
                        viewModel = viewModel { LoadViewModel { MusicHome(client.musicHome().shelves, client.genres().genres) } },
                        onOpen = open,
                        onGrid = openGrid,
                    )
                }
                composable<BooksRoute> {
                    BooksScreen(
                        viewModel = viewModel { LoadViewModel { client.booksHome().shelves } },
                        onOpen = open,
                        onGrid = openGrid,
                    )
                }
                composable<SearchRoute> {
                    SearchScreen(
                        viewModel = viewModel { SearchViewModel(client) },
                        onOpen = open,
                        onTab = selectTab,
                        onGrid = openGrid,
                    )
                }
                composable<GridRoute> { entry ->
                    val route = entry.toRoute<GridRoute>()
                    CardGridScreen(
                        title = route.title,
                        viewModel = viewModel { CardGridViewModel(client, GridSource.decode(route.source)) },
                        onOpen = open,
                        onBack = back,
                    )
                }
                composable<DetailRoute> { entry ->
                    val route = entry.toRoute<DetailRoute>()
                    if (route.kind in VIDEO_KINDS) {
                        VideoDetailScreen(
                            viewModel = viewModel { ItemDetailViewModel(client, route.id) },
                            onOpen = open,
                            onBack = back,
                        )
                    } else {
                        MediaDetailScreen(
                            viewModel = viewModel { MediaDetailViewModel(client, route.kind, route.id) },
                            onOpen = open,
                            onBack = back,
                        )
                    }
                }
            }
            BottomBar(
                selected = currentTab,
                onSelect = selectTab,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (showAccount) {
            AccountSheet(
                session = state.session,
                user = user,
                onDismiss = { showAccount = false },
                onSwitchProfile = {
                    showAccount = false
                    scope.launch { session.switchProfile() }
                },
                onSignOut = {
                    showAccount = false
                    scope.launch { session.signOut() }
                },
                onChangeServer = {
                    showAccount = false
                    scope.launch { session.changeServer() }
                },
            )
        }
    }
}

/** Switches to [tab] keeping each tab's stack; re-selecting the current tab returns to its root. */
private fun NavHostController.selectTab(tab: NavTab, reselect: Boolean) {
    if (reselect) {
        popBackStack(tab.route, inclusive = false)
        return
    }
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

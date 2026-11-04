package app.antresol.tv.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.antresol.core.model.ShuffleSource
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.tv.AntresolTvApp
import app.antresol.tv.R as TvR
import app.antresol.tv.playback.AudioContent
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.browse.CardGridScreen
import app.antresol.tv.ui.browse.CardGridViewModel
import app.antresol.tv.ui.browse.GridKind
import app.antresol.tv.ui.browse.GridSource
import app.antresol.tv.ui.browse.ShelvesScreen
import app.antresol.tv.ui.browse.ShelvesViewModel
import app.antresol.tv.ui.comics.ComicReaderScreen
import app.antresol.tv.ui.comics.ComicReaderViewModel
import app.antresol.tv.ui.components.LinkButton
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.TvIcons
import app.antresol.tv.ui.detail.DetailScreen
import app.antresol.tv.ui.detail.DetailViewModel
import app.antresol.tv.ui.home.HomeScreen
import app.antresol.tv.ui.home.HomeViewModel
import app.antresol.tv.ui.music.SongsScreen
import app.antresol.tv.ui.music.SongsViewModel
import app.antresol.tv.ui.nowplaying.NowPlayingScreen
import app.antresol.tv.ui.nowplaying.NowPlayingViewModel
import app.antresol.tv.ui.player.PlayerScreen
import app.antresol.tv.ui.player.PlayerViewModel
import app.antresol.tv.ui.player.ResumeMode
import app.antresol.tv.ui.search.SearchScreen
import app.antresol.tv.ui.search.SearchViewModel
import app.antresol.tv.ui.settings.SettingsScreen
import app.antresol.tv.ui.settings.SettingsViewModel
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
    val app = LocalContext.current.applicationContext as AntresolTvApp
    val audio = app.audio
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val selectedTab = NavTab.entries.firstOrNull { tab ->
        backStack?.destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
    }
    val onSettings = backStack?.destination?.hasRoute(SettingsRoute::class) == true
    val navVisible = selectedTab != null || onSettings
    var contentAtTop by remember { mutableStateOf(true) }
    var navFocused by remember { mutableStateOf(false) }
    val showNav = navVisible && (navFocused || contentAtTop)
    val navAlpha by animateFloatAsState(if (showNav) 1f else 0f, tween(AntresolMotion.MEDIUM_MS), label = "nav-alpha")

    val open: (Card) -> Unit = { card -> navController.navigate(DetailRoute(card)) }
    val play: (Long, ResumeMode) -> Unit = { id, mode ->
        // Video takes over the screen and the speakers.
        audio.pause()
        navController.navigate(PlayerRoute(id, mode.name))
    }
    // Hero Play and Continue Watching: video cards resume in the player, anything else opens its page.
    val playCard: (Card) -> Unit = { card ->
        if (card.kind in PLAYABLE_KINDS) play(card.id, ResumeMode.Resume) else open(card)
    }
    val nowPlaying: () -> Unit = { navController.navigate(NowPlayingRoute) { launchSingleTop = true } }
    val openGrid: (GridKind, String?) -> Unit = { kind, genre -> navController.navigate(GridRoute(kind.name, genre.orEmpty())) }
    val shuffle: (ShuffleSource) -> Unit = { source ->
        scope.launch {
            val queue = runCatching { client.shuffle(source).tracks }.getOrDefault(emptyList())
            if (queue.isNotEmpty()) {
                audio.playTracks(queue)
                nowPlaying()
            }
        }
    }
    val canTakeFocus = { !navFocused }
    val onScrolledToTop: (Boolean) -> Unit = { contentAtTop = it }

    // The system's now-playing card was opened.
    val nowPlayingRequested by app.nowPlayingRequested.collectAsStateWithLifecycle()
    LaunchedEffect(nowPlayingRequested) {
        if (nowPlayingRequested) {
            app.nowPlayingRequested.value = false
            if (audio.content != null) nowPlaying()
        }
    }

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
                        onPlay = playCard,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                    )
                }
                composable<MoviesRoute> {
                    LibraryGrid(GridSource(GridKind.Movies), stringResource(R.string.nav_movies), open, onScrolledToTop, canTakeFocus)
                }
                composable<ShowsRoute> {
                    LibraryGrid(GridSource(GridKind.Shows), stringResource(R.string.nav_tv_shows), open, onScrolledToTop, canTakeFocus)
                }
                composable<MusicRoute> {
                    ShelvesScreen(
                        title = stringResource(R.string.nav_music),
                        viewModel = viewModel { ShelvesViewModel({ client.musicHome().shelves }, { client.genres().genres }) },
                        onOpen = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                        onGenre = { genre -> openGrid(GridKind.Albums, genre.name) },
                        actions = {
                            LinkButton(stringResource(TvR.string.music_all_albums), onClick = { openGrid(GridKind.Albums, null) })
                            LinkButton(stringResource(TvR.string.music_artists), onClick = { openGrid(GridKind.Artists, null) })
                            LinkButton(stringResource(TvR.string.music_songs), onClick = { navController.navigate(SongsRoute) })
                            LinkButton(stringResource(TvR.string.music_playlists), onClick = { openGrid(GridKind.Playlists, null) })
                            PillButton(stringResource(TvR.string.music_shuffle_all), onClick = { shuffle(ShuffleSource.All) }, icon = TvIcons.Shuffle)
                        },
                    )
                }
                composable<BooksRoute> {
                    ShelvesScreen(
                        title = stringResource(R.string.nav_books),
                        viewModel = viewModel { ShelvesViewModel({ client.booksHome().shelves }) },
                        onOpen = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                        actions = {
                            LinkButton(stringResource(TvR.string.books_all), onClick = { openGrid(GridKind.Books, null) })
                            LinkButton(stringResource(TvR.string.books_audiobooks), onClick = { openGrid(GridKind.Audiobooks, null) })
                            LinkButton(stringResource(TvR.string.books_comics), onClick = { openGrid(GridKind.Comics, null) })
                        },
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
                composable<SettingsRoute> {
                    SettingsScreen(
                        state = state,
                        viewModel = viewModel { SettingsViewModel(client) },
                        settings = app.settings,
                        onSwitchProfile = { scope.launch { session.switchProfile() } },
                        onSignOut = { scope.launch { session.signOut() } },
                        onChangeServer = { scope.launch { session.changeServer() } },
                        onScrolledToTop = onScrolledToTop,
                    )
                }
                composable<GridRoute> { entry ->
                    val route = entry.toRoute<GridRoute>()
                    val source = GridSource(GridKind.valueOf(route.kind), route.genre.ifEmpty { null })
                    LibraryGrid(
                        source = source,
                        title = source.genre ?: stringResource(gridTitle(source.kind)),
                        open = open,
                        onScrolledToTop = {},
                        canTakeFocus = { true },
                        onShuffle = source.shuffle?.let { shuffleSource -> { shuffle(shuffleSource) } },
                    )
                }
                composable<SongsRoute> {
                    SongsScreen(viewModel = viewModel { SongsViewModel(client, audio) }, audio = audio, onNowPlaying = nowPlaying)
                }
                composable<NowPlayingRoute> {
                    NowPlayingScreen(viewModel = viewModel { NowPlayingViewModel(client, audio) })
                }
                composable<DetailRoute> { entry ->
                    val route = entry.toRoute<DetailRoute>()
                    DetailScreen(
                        viewModel = viewModel { DetailViewModel(client, audio, route.kind, route.id) },
                        audio = audio,
                        onOpen = open,
                        onPlay = play,
                        onNowPlaying = nowPlaying,
                        onRead = { volumeId -> navController.navigate(ComicReaderRoute(volumeId)) },
                    )
                }
                composable<ComicReaderRoute> { entry ->
                    val route = entry.toRoute<ComicReaderRoute>()
                    ComicReaderScreen(
                        viewModel = viewModel { ComicReaderViewModel(client, app.appScope, route.volumeId) },
                        onOpenVolume = { next ->
                            navController.navigate(ComicReaderRoute(next)) {
                                popUpTo<ComicReaderRoute> { inclusive = true }
                            }
                        },
                        onExit = { navController.popBackStack() },
                    )
                }
                composable<PlayerRoute> { entry ->
                    val route = entry.toRoute<PlayerRoute>()
                    PlayerScreen(
                        viewModel = viewModel {
                            PlayerViewModel(app, client, app.appScope, route.itemId, ResumeMode.valueOf(route.resume), app.settings.current)
                        },
                        onExit = { navController.popBackStack() },
                    )
                }
            }
            if (navVisible) {
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
                    nowPlaying = audio.currentTrack?.title ?: (audio.content as? AudioContent.Audiobook)?.book?.card?.title,
                    nowPlayingActive = audio.isPlaying,
                    onSelect = { tab -> navController.navigateToTab(tab) },
                    onNowPlaying = nowPlaying,
                    onSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } },
                    onFocusChanged = { navFocused = it },
                    modifier = Modifier.graphicsLayer { alpha = navAlpha },
                )
            }
        }
    }
}

@Composable
private fun LibraryGrid(
    source: GridSource,
    title: String,
    open: (Card) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
    onShuffle: (() -> Unit)? = null,
) {
    val client = (LocalContext.current.applicationContext as AntresolTvApp).session.requireClient()
    val resources = LocalContext.current.resources
    CardGridScreen(
        title = title,
        viewModel = viewModel(key = source.toString()) {
            CardGridViewModel(client, source) { count -> resources.getString(R.string.detail_song_count, count) }
        },
        onOpen = open,
        onScrolledToTop = onScrolledToTop,
        canTakeFocus = canTakeFocus,
        onShuffle = onShuffle,
    )
}

private fun gridTitle(kind: GridKind): Int = when (kind) {
    GridKind.Movies -> R.string.nav_movies
    GridKind.Shows -> R.string.nav_tv_shows
    GridKind.Albums -> TvR.string.music_all_albums
    GridKind.Artists -> TvR.string.music_artists
    GridKind.Playlists -> TvR.string.music_playlists
    GridKind.Books -> TvR.string.books_all
    GridKind.Audiobooks -> TvR.string.books_audiobooks
    GridKind.Comics -> TvR.string.books_comics
}

private val PLAYABLE_KINDS = setOf(Kind.MOVIE, Kind.EPISODE, Kind.SERIES, Kind.SEASON)

/** Switches tabs the TV way: one entry per tab, each keeping its own scroll and focus state. */
private fun NavHostController.navigateToTab(tab: NavTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

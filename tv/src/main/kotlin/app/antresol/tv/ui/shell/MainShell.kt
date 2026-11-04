package app.antresol.tv.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.components.GlassNotice
import app.antresol.tv.ui.load
import app.antresol.tv.ui.userMessage
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
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
    // The shell's own ViewModel store: its back stack ViewModels (a playing video, a comic's final
    // save) are cleared when the profile changes or the session expires, not when the Activity dies.
    // Remembered first, so it is cleared after the NavHost left composition.
    val viewModelStoreOwner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(viewModelStoreOwner) { onDispose { viewModelStoreOwner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
        ShellContent(session, state)
    }
}

@Composable
private fun ShellContent(session: SessionManager, state: AuthState.SignedIn) {
    val client = session.requireClient()
    val app = LocalContext.current.applicationContext as AntresolTvApp
    val audio = app.audio
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val selectedTab = NavTab.entries.firstOrNull { tab ->
        backStack?.destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
    }
    // Full-screen experiences hide the nav; detail, grid and settings pages keep it (no tab selected).
    val fullScreen = backStack?.destination?.let { destination ->
        destination.hasRoute(PlayerRoute::class) || destination.hasRoute(ComicReaderRoute::class) ||
            destination.hasRoute(NowPlayingRoute::class)
    } ?: true
    var settingsMenuOpen by remember { mutableStateOf(false) }
    val navVisible = !fullScreen && !settingsMenuOpen
    var contentAtTop by remember { mutableStateOf(true) }
    LaunchedEffect(backStack?.id) {
        contentAtTop = true
        settingsMenuOpen = false
    }
    var notice by remember { mutableStateOf<String?>(null) }
    val resources = LocalContext.current.resources
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
            when (val queue = load { client.shuffle(source).tracks }) {
                is LoadState.Ready -> if (queue.value.isEmpty()) {
                    notice = resources.getString(TvR.string.music_nothing_to_shuffle)
                } else {
                    audio.playTracks(queue.value)
                    nowPlaying()
                }
                is LoadState.Failed -> notice = queue.error.userMessage(resources)
                LoadState.Loading -> Unit
            }
        }
    }
    // Down from the nav enters the page (see [Page]).
    val contentFocus = remember { FocusRequester() }
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
            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                modifier = Modifier.fillMaxSize(),
            ) {
                page<HomeRoute>(contentFocus) {
                    HomeScreen(
                        viewModel = viewModel { HomeViewModel(client) },
                        onOpen = open,
                        onPlay = playCard,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                    )
                }
                page<MoviesRoute>(contentFocus) {
                    LibraryGrid(GridSource(GridKind.Movies), stringResource(R.string.nav_movies), open, onScrolledToTop, canTakeFocus)
                }
                page<ShowsRoute>(contentFocus) {
                    LibraryGrid(GridSource(GridKind.Shows), stringResource(R.string.nav_tv_shows), open, onScrolledToTop, canTakeFocus)
                }
                page<MusicRoute>(contentFocus) {
                    ShelvesScreen(
                        title = stringResource(R.string.nav_music),
                        viewModel = viewModel { ShelvesViewModel({ client.musicHome().shelves }, { client.genres().genres }) },
                        onOpen = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                        onGenre = { genre -> openGrid(GridKind.Albums, genre.name) },
                        actions = { link ->
                            LinkButton(stringResource(TvR.string.music_all_albums), { openGrid(GridKind.Albums, null) }, Modifier.link("albums"))
                            LinkButton(stringResource(TvR.string.music_artists), { openGrid(GridKind.Artists, null) }, Modifier.link("artists"))
                            LinkButton(stringResource(TvR.string.music_songs), { navController.navigate(SongsRoute) }, Modifier.link("songs"))
                            LinkButton(stringResource(TvR.string.music_playlists), { openGrid(GridKind.Playlists, null) }, Modifier.link("playlists"))
                            PillButton(
                                stringResource(TvR.string.music_shuffle_all),
                                onClick = { shuffle(ShuffleSource.All) },
                                icon = TvIcons.Shuffle,
                                modifier = Modifier.link("shuffle"),
                            )
                        },
                    )
                }
                page<BooksRoute>(contentFocus) {
                    ShelvesScreen(
                        title = stringResource(R.string.nav_books),
                        viewModel = viewModel { ShelvesViewModel({ client.booksHome().shelves }) },
                        onOpen = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                        actions = { link ->
                            LinkButton(stringResource(TvR.string.books_all), { openGrid(GridKind.Books, null) }, Modifier.link("books"))
                            LinkButton(stringResource(TvR.string.books_audiobooks), { openGrid(GridKind.Audiobooks, null) }, Modifier.link("audiobooks"))
                            LinkButton(stringResource(TvR.string.books_comics), { openGrid(GridKind.Comics, null) }, Modifier.link("comics"))
                        },
                    )
                }
                page<SearchRoute>(contentFocus) {
                    SearchScreen(
                        viewModel = viewModel { SearchViewModel(client) },
                        onOpen = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                    )
                }
                page<SettingsRoute>(contentFocus) {
                    SettingsScreen(
                        state = state,
                        viewModel = viewModel { SettingsViewModel(client) },
                        settings = app.settings,
                        onSwitchProfile = { scope.launch { session.switchProfile() } },
                        onSignOut = { scope.launch { session.signOut() } },
                        onChangeServer = { scope.launch { session.changeServer() } },
                        onScrolledToTop = onScrolledToTop,
                        onMenuOpenChanged = { settingsMenuOpen = it },
                    )
                }
                page<GridRoute>(contentFocus) { entry ->
                    val route = entry.toRoute<GridRoute>()
                    val source = GridSource(GridKind.valueOf(route.kind), route.genre.ifEmpty { null })
                    LibraryGrid(
                        source = source,
                        title = source.genre ?: stringResource(gridTitle(source.kind)),
                        open = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                        onShuffle = source.shuffle?.let { shuffleSource -> { shuffle(shuffleSource) } },
                    )
                }
                page<SongsRoute>(contentFocus) {
                    SongsScreen(
                        viewModel = viewModel { SongsViewModel(client, audio, app.appScope) },
                        audio = audio,
                        onNowPlaying = nowPlaying,
                        onScrolledToTop = onScrolledToTop,
                    )
                }
                page<NowPlayingRoute>(contentFocus) {
                    NowPlayingScreen(viewModel = viewModel { NowPlayingViewModel(client, audio) })
                }
                page<DetailRoute>(contentFocus) { entry ->
                    val route = entry.toRoute<DetailRoute>()
                    DetailScreen(
                        viewModel = viewModel { DetailViewModel(client, audio, route.kind, route.id) },
                        audio = audio,
                        onOpen = open,
                        onPlay = play,
                        onNowPlaying = nowPlaying,
                        onRead = { volumeId, fromStart -> navController.navigate(ComicReaderRoute(volumeId, fromStart)) },
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                    )
                }
                composable<ComicReaderRoute> { entry ->
                    val route = entry.toRoute<ComicReaderRoute>()
                    ComicReaderScreen(
                        viewModel = viewModel { ComicReaderViewModel(client, app.appScope, route.volumeId, route.fromStart) },
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
                    contentFocus = contentFocus,
                    modifier = Modifier.graphicsLayer { alpha = navAlpha },
                )
            }
            notice?.let { message ->
                LaunchedEffect(message) {
                    delay(NOTICE_MS)
                    notice = null
                }
                GlassNotice(message, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
            }
        }
    }
}

private const val NOTICE_MS = 3_500L

/**
 * A destination whose page can be entered from the nav with Down. The page spans the whole screen
 * behind the nav, so geometric focus search never finds it below the pills; the nav instead
 * focuses this group, which restores the element focused last on this page.
 */
@OptIn(ExperimentalComposeUiApi::class)
private inline fun <reified T : Any> NavGraphBuilder.page(
    contentFocus: FocusRequester,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(contentFocus)
                .focusRestorer(),
        ) {
            content(entry)
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
            CardGridViewModel(client, source) { count -> resources.getQuantityString(R.plurals.song_count, count, count) }
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

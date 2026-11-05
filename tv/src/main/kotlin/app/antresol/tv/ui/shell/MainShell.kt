package app.antresol.tv.ui.shell

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.browse.CardGridScreen
import app.antresol.tv.ui.browse.CardGridViewModel
import app.antresol.tv.ui.browse.GridKind
import app.antresol.tv.ui.browse.GridSource
import app.antresol.tv.ui.browse.ShelvesScreen
import app.antresol.tv.ui.browse.ShelvesViewModel
import app.antresol.tv.ui.comics.ComicReaderScreen
import app.antresol.tv.ui.comics.ComicReaderViewModel
import app.antresol.tv.ui.components.GlassNotice
import app.antresol.tv.ui.components.LinkButton
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.TvIcons
import app.antresol.tv.ui.detail.DetailScreen
import app.antresol.tv.ui.detail.DetailViewModel
import app.antresol.tv.ui.home.HomeScreen
import app.antresol.tv.ui.home.HomeViewModel
import app.antresol.tv.ui.load
import app.antresol.tv.ui.music.SongsScreen
import app.antresol.tv.ui.music.SongsViewModel
import app.antresol.tv.ui.nowplaying.NowPlayingScreen
import app.antresol.tv.ui.nowplaying.NowPlayingViewModel
import app.antresol.tv.ui.player.PlayerScreen
import app.antresol.tv.ui.player.PlayerViewModel
import app.antresol.tv.ui.search.BrowseTarget
import app.antresol.tv.ui.search.SearchScreen
import app.antresol.tv.ui.search.SearchViewModel
import app.antresol.tv.ui.settings.SettingsScreen
import app.antresol.tv.ui.settings.SettingsViewModel
import app.antresol.tv.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.playback.ResumeMode
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The signed-in app: top pill navigation over a NavHost of tab screens and detail pages.
 * The navigation hides while a tab's content is scrolled down and comes back when it gets focus.
 */
@Composable
fun MainShell(session: SessionManager, state: AuthState.SignedIn) {
    // The shell's own ViewModel store: its back stack ViewModels (a playing video, a comic's final
    // save) are cleared when the profile changes or the session expires. It lives in an
    // Activity-retained holder, so a configuration change that recreates the Activity (a locale
    // change) keeps playback and page state. Taken first, so it is cleared after the NavHost left.
    val stores: ShellStores = viewModel()
    val key = state.session.serverId to state.session.userId
    val viewModelStoreOwner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore = stores.storeFor(key)
        }
    }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(viewModelStoreOwner) {
        onDispose { if (activity?.isChangingConfigurations != true) stores.release(key) }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
        ShellContent(session, state)
    }
}

/** Per-profile ViewModel stores of the shell, retained across Activity recreation. */
class ShellStores : ViewModel() {
    private val stores = HashMap<Pair<String, Long>, ViewModelStore>()

    fun storeFor(key: Pair<String, Long>): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    fun release(key: Pair<String, Long>) {
        stores.remove(key)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
    // The nearest tab below the current page in the back stack: the section a detail page belongs to.
    val stack by navController.currentBackStack.collectAsStateWithLifecycle()
    val contextTab = stack.asReversed().firstNotNullOfOrNull { entry ->
        NavTab.entries.firstOrNull { tab -> entry.destination.hasRoute(tab.route::class) }
    }
    // Full-screen experiences hide the nav; detail, grid and settings pages keep it (no tab selected).
    val fullScreen = backStack?.destination?.let { destination ->
        destination.hasRoute(PlayerRoute::class) || destination.hasRoute(ComicReaderRoute::class) ||
            destination.hasRoute(NowPlayingRoute::class)
    } ?: true
    var panelOpen by remember { mutableStateOf(false) }
    val navVisible = !fullScreen && !panelOpen
    var contentAtTop by remember { mutableStateOf(true) }
    LaunchedEffect(backStack?.id) {
        contentAtTop = true
        panelOpen = false
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
    // Shelf titles with a full list behind them link to it.
    val seeAll: (String) -> (() -> Unit)? = { shelfId -> SHELF_GRIDS[shelfId]?.let { kind -> { openGrid(kind, null) } } }
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
    // Down from the nav enters the current page (see [page]).
    val contentFocus = remember { PageFocus() }
    contentFocus.currentEntryId = backStack?.id
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
                // Short fades: the default 700 ms feels slow on a TV and keeps both pages composed.
                enterTransition = { fadeIn(tween(AntresolMotion.FAST_MS)) },
                exitTransition = { fadeOut(tween(AntresolMotion.FAST_MS)) },
                popEnterTransition = { fadeIn(tween(AntresolMotion.FAST_MS)) },
                popExitTransition = { fadeOut(tween(AntresolMotion.FAST_MS)) },
            ) {
                page<HomeRoute>(contentFocus) {
                    HomeScreen(
                        viewModel = viewModel { HomeViewModel(client, app.pendingWrites) },
                        onOpen = open,
                        onPlay = playCard,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                        seeAll = seeAll,
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
                        seeAll = seeAll,
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
                        seeAll = seeAll,
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
                        viewModel = viewModel { SearchViewModel(client, audio) },
                        onOpen = open,
                        onNowPlaying = nowPlaying,
                        onBrowse = { target ->
                            when (target) {
                                BrowseTarget.Movies -> navController.navigateToTab(NavTab.Movies)
                                BrowseTarget.Shows -> navController.navigateToTab(NavTab.Shows)
                                BrowseTarget.Music -> navController.navigateToTab(NavTab.Music)
                                BrowseTarget.Books -> openGrid(GridKind.Books, null)
                                BrowseTarget.Audiobooks -> openGrid(GridKind.Audiobooks, null)
                                BrowseTarget.Comics -> openGrid(GridKind.Comics, null)
                            }
                        },
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
                        onMenuOpenChanged = { panelOpen = it },
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
                        viewModel = viewModel { DetailViewModel(client, audio, route.kind, route.id, app.pendingWrites) },
                        audio = audio,
                        onOpen = open,
                        onPlay = play,
                        onNowPlaying = nowPlaying,
                        onRead = { volumeId, fromStart -> navController.navigate(ComicReaderRoute(volumeId, fromStart)) },
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = canTakeFocus,
                        onPanelOpenChanged = { panelOpen = it },
                        onClose = { navController.popBackStack() },
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
                            PlayerViewModel(app, client, app.appScope, route.itemId, ResumeMode.valueOf(route.resume), app.settings.current, app.pendingWrites)
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
                    contextTab = contextTab,
                    userName = state.session.userName,
                    avatar = state.session.avatar,
                    nowPlaying = audio.currentTrack?.title ?: (audio.content as? AudioContent.Audiobook)?.book?.card?.title,
                    nowPlayingActive = audio.isPlaying,
                    onSelect = { tab -> navController.navigateToTab(tab) },
                    onNowPlaying = nowPlaying,
                    onSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } },
                    onFocusChanged = { navFocused = it },
                    contentFocus = contentFocus::current,
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
 * One focus requester per back stack entry. During a page transition both pages are composed; the
 * nav must enter only the current one, never the page that is leaving.
 */
private class PageFocus {
    private val requesters = HashMap<String, FocusRequester>()

    var currentEntryId: String? = null

    fun requesterFor(entryId: String): FocusRequester = requesters.getOrPut(entryId) { FocusRequester() }

    fun release(entryId: String) {
        requesters.remove(entryId)
    }

    fun current(): FocusRequester? = currentEntryId?.let(requesters::get)
}

/**
 * A destination whose page can be entered from the nav with Down. The page spans the whole screen
 * behind the nav, so geometric focus search never finds it below the pills; the nav instead
 * focuses this group, which restores the element focused last on this page.
 */
@OptIn(ExperimentalComposeUiApi::class)
private inline fun <reified T : Any> NavGraphBuilder.page(
    contentFocus: PageFocus,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        val requester = remember(entry.id) { contentFocus.requesterFor(entry.id) }
        DisposableEffect(entry.id) { onDispose { contentFocus.release(entry.id) } }
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(requester)
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

/** Server shelf ids (Home, Music, Books) and the grid that lists all of it. */
private val SHELF_GRIDS = mapOf(
    "recent-movies" to GridKind.Movies,
    "recent-series" to GridKind.Shows,
    "recent-albums" to GridKind.Albums,
    "artists" to GridKind.Artists,
    "playlists" to GridKind.Playlists,
    "audiobooks" to GridKind.Audiobooks,
    "comics" to GridKind.Comics,
)

/** Switches tabs the TV way: one entry per tab, each keeping its own scroll and focus state. */
private fun NavHostController.navigateToTab(tab: NavTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

package app.antresol.tv.ui.shell

import android.app.Activity
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import app.antresol.tv.ui.components.LocalFocusMemories
import app.antresol.tv.ui.components.FocusMemory
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.runtime.State
import kotlinx.coroutines.Job
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.activity.compose.BackHandler
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import android.content.ContextWrapper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import app.antresol.ui.LoadState
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.browse.CardGridScreen
import app.antresol.tv.ui.browse.CardGridViewModel
import app.antresol.tv.ui.browse.GridKind
import app.antresol.tv.ui.browse.GridSource
import app.antresol.tv.ui.browse.ShelvesScreen
import app.antresol.tv.ui.browse.ShelvesViewModel
import app.antresol.tv.ui.channels.ChannelsScreen
import app.antresol.tv.ui.comics.ComicReaderScreen
import app.antresol.tv.ui.comics.ComicReaderViewModel
import app.antresol.tv.ui.components.GlassNotice
import app.antresol.tv.ui.components.HeldKeyGuard
import app.antresol.tv.ui.components.LinkButton
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.TvIcons
import app.antresol.tv.ui.detail.DetailScreen
import app.antresol.tv.ui.detail.DetailViewModel
import app.antresol.tv.ui.home.HomeScreen
import app.antresol.tv.ui.home.HomeViewModel
import app.antresol.core.ways.WayTicket
import app.antresol.core.ways.WayPlayback
import app.antresol.ui.load
import app.antresol.tv.ui.music.SongsScreen
import app.antresol.tv.ui.music.SongsViewModel
import app.antresol.tv.ui.nowplaying.NowPlayingScreen
import app.antresol.tv.ui.nowplaying.NowPlayingViewModel
import app.antresol.tv.homechannels.HomeLink
import app.antresol.tv.ui.player.LivePlayerScreen
import app.antresol.tv.ui.player.PlayerScreen
import app.antresol.tv.ui.player.TrailerEmbedScreen
import app.antresol.tv.ui.player.TrailerPlayerScreen
import app.antresol.ui.trailers.TrailerPlayerViewModel
import app.antresol.tv.ui.player.PlayerViewModel
import app.antresol.tv.ui.search.BrowseTarget
import app.antresol.tv.ui.search.SearchScreen
import app.antresol.tv.ui.search.SearchViewModel
import app.antresol.tv.ui.settings.SettingsScreen
import app.antresol.ui.userMessage
import app.antresol.ui.R
import app.antresol.core.channels.ChannelFilter
import app.antresol.tv.autostart.AutostartTarget
import app.antresol.tv.autostart.ChannelAutostart
import app.antresol.tv.autostart.ChannelStart
import app.antresol.tv.autostart.LastChannel
import app.antresol.core.channels.LiveApi
import app.antresol.core.channels.liveApi
import app.antresol.core.channels.LiveTvSession
import app.antresol.ui.channels.ChannelPrefsModel
import app.antresol.ui.channels.ChannelsView
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.channels.channelPrefsApi
import app.antresol.ui.channels.channelsApi
import app.antresol.ui.language.UiLanguageModel
import app.antresol.ui.history.DeviceHistoryModel
import app.antresol.ui.history.deviceHistoryApi
import app.antresol.ui.language.uiLanguageApi
import app.antresol.tv.ui.settings.restartInNewLanguage
import app.antresol.ui.playback.LivePlayerViewModel
import app.antresol.ui.playback.DeviceCaps
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
    val profile = state.session.serverId to state.session.userId
    // «Отдельная история на этом устройстве» switched (here, or for this TV on the web): every page
    // shows another history now (Home's Continue Watching, watched marks, progress), so the screens
    // start again as after a profile switch. The first answer of `/api/me` is not a switch.
    val history = state.user?.let { it.deviceHistory?.id ?: 0L }
    var shownHistory by rememberSaveable(profile) { mutableStateOf(history) }
    var generation by rememberSaveable(profile) { mutableIntStateOf(0) }
    if (history != null && history != shownHistory) {
        if (shownHistory != null) generation++
        shownHistory = history
    }
    val key = ShellKey(profile.first, profile.second, generation)
    val viewModelStoreOwner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore = stores.storeFor(key)
        }
    }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(viewModelStoreOwner) {
        onDispose { if (activity?.isChangingConfigurations != true) stores.release(key) }
    }
    key(key) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
            ShellContent(session, state)
        }
    }
}

/** Whose screens the shell shows: a profile on a server, and how often its device's history changed since. */
data class ShellKey(val serverId: String, val userId: Long, val historyGeneration: Int)

/** Per-profile ViewModel stores of the shell, retained across Activity recreation. */
class ShellStores : ViewModel() {
    private val stores = HashMap<ShellKey, ViewModelStore>()

    fun storeFor(key: ShellKey): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    fun release(key: ShellKey) {
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
    // The interface follows the profile's language: a different one restarts the screens in it.
    val hostActivity = LocalContext.current.findActivity()
    val uiLanguage: UiLanguageModel = viewModel(key = "ui-language") {
        UiLanguageModel(client.uiLanguageApi(), app.settings) { hostActivity?.let(::restartInNewLanguage) }
    }
    LaunchedEffect(uiLanguage) { uiLanguage.refresh() }
    val backStack by navController.currentBackStackEntryAsState()
    val selectedTab = NavTab.entries.firstOrNull { tab ->
        backStack?.destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
    }
    // The nearest tab below the current page in the back stack: the section a detail page belongs to.
    // Settings belongs to the profile avatar, not to the tab it was opened from.
    val stack by navController.currentBackStack.collectAsStateWithLifecycle()
    val inSettings = backStack?.destination?.hasRoute(SettingsRoute::class) == true
    val contextTab = if (inSettings) {
        null
    } else {
        stack.asReversed().firstNotNullOfOrNull { entry ->
            NavTab.entries.firstOrNull { tab -> entry.destination.hasRoute(tab.route::class) }
        }
    }
    // Full-screen experiences hide the nav; detail, grid and settings pages keep it (no tab selected).
    val fullScreen = backStack?.destination?.let { destination ->
        destination.hasRoute(PlayerRoute::class) || destination.hasRoute(ComicReaderRoute::class) ||
            destination.hasRoute(NowPlayingRoute::class) || destination.hasRoute(LiveRoute::class) ||
            destination.hasRoute(TrailerRoute::class) ||
            destination.hasRoute(ShellRootRoute::class)
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

    // A title off the library (an addon's search result) opens the page made from its TMDB id; anything else its card.
    val open: (Card) -> Unit = { card ->
        if (card.opensTitlePage) navController.navigate(TitleRoute(card.kind, card.tmdbId ?: 0)) else navController.navigate(DetailRoute(card))
    }
    val play: (Long, ResumeMode) -> Unit = { id, mode ->
        // Video takes over the screen and the speakers.
        audio.pause()
        navController.navigate(PlayerRoute(id, mode.name))
    }
    // A way may pick a quality by what the TV shows: the display and the decoder limit it (as the player does).
    val wayMaxHeight: suspend () -> Int? = {
        withContext(Dispatchers.Default) { runCatching { DeviceCaps.current(app, limitToDisplay = true).maxHeight }.getOrNull() }
    }
    // What an addon's way answered with plays in the same player.
    val playWay: (WayPlayback) -> Unit = { playback ->
        audio.pause()
        navController.navigate(PlayerRoute(0, ResumeMode.Resume.name, WayTicket.put(playback)))
    }
    // Hero Play and Continue Watching: video cards resume in the player, anything else opens its page.
    val playCard: (Card) -> Unit = { card ->
        if (card.kind in Kind.VIDEO) play(card.id, ResumeMode.Resume) else open(card)
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
    contentFocus.currentEntry = navController.currentBackStackEntryAsState()
    // Pages take focus only while the nav does not have it, and only the current page: a page
    // that is leaving (its fade-out still composed) must never pull focus to itself.
    contentFocus.navFocused = { navFocused }

    // Tabs: a tab left for another one resets to its root after TAB_RESET_MS, unless the user
    // comes back first (an accidental switch keeps everything).
    val resets = remember { HashMap<NavTab, Job>() }
    // The live player's Guide key: the TV tab opens on its guide.
    val guideRequests = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val selectTab: (NavTab) -> Unit = { tab ->
        val leaving = navController.currentTab()
        resets.remove(tab)?.cancel()
        if (tab == leaving) {
            // The current tab again: back to its root.
            navController.popBackStack(tab.route, inclusive = false)
        } else {
            navController.navigateToTab(tab)
            if (leaving != null) {
                resets.remove(leaving)?.cancel()
                resets[leaving] = scope.launch {
                    delay(TAB_RESET_MS)
                    resets.remove(leaving)
                    navController.clearBackStack(leaving.route)
                }
            }
        }
    }
    // The shell opens on Home, above the root (a restored back stack is left as it was).
    LaunchedEffect(navController) {
        if (navController.currentBackStackEntry?.destination?.hasRoute(ShellRootRoute::class) == true) {
            navController.navigate(HomeRoute)
        }
    }
    // Back on a tab's root: another tab goes to Home, Home leaves the app (the root stays hidden).
    val activity = LocalContext.current.findActivity()
    contentFocus.onTabRootBack = { tab -> if (tab == NavTab.Home) activity?.moveTaskToBack(true) else selectTab(NavTab.Home) }
    // Safety net: once a page has settled, focus is on it or on the nav, never nowhere (a TV
    // without focus ignores every key and looks frozen). Back pressed during the forward
    // transition returns to a page that never left composition, so its own arrival effects do not
    // run again, while the focused element of the page that was opening goes away with it. Checked
    // a few times: that element can take focus with it as it is detached after the transition.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(backStack?.id) {
        delay(AntresolMotion.FAST_MS.toLong())
        repeat(SETTLE_CHECKS) {
            delay(SETTLE_STEP_MS)
            if (!navFocused && !panelOpen) contentFocus.ensureFocus(focusManager)
        }
    }
    // And whenever the current page loses focus later on without the nav or a panel taking it
    // (the leftovers of the page that was opening are detached when its exit settles).
    LaunchedEffect(contentFocus) {
        snapshotFlow { contentFocus.focusLost }.collect {
            delay(SETTLE_STEP_MS)
            if (!navFocused && !panelOpen) contentFocus.ensureFocus(focusManager)
        }
    }
    val onScrolledToTop: (Boolean) -> Unit = { contentAtTop = it }

    // The system's now-playing card was opened.
    val nowPlayingRequested by app.nowPlayingRequested.collectAsStateWithLifecycle()
    LaunchedEffect(nowPlayingRequested) {
        if (nowPlayingRequested) {
            app.nowPlayingRequested.value = false
            if (audio.content != null) nowPlaying()
        }
    }

    // "Open Antresol at power on" started the app: show what the setting says, once, and only while
    // the request is fresh (an old one belongs to a start the viewer has long since taken over).
    // Collected, not keyed: clearing the request must not cancel the work it started.
    val signedIn by rememberUpdatedState(state)
    LaunchedEffect(app) {
        app.autostartRequested.filterNotNull().collect { request ->
            app.autostartRequested.value = null
            if (!request.fresh(SystemClock.elapsedRealtime())) return@collect
            // The channel this profile was watching when the app last left the front, as the device remembers it.
            val record = ChannelAutostart.recordFor(
                app.lastChannel.get(), signedIn.session.serverId, signedIn.session.userId,
                historyId = signedIn.user?.deviceHistory?.id, historyKnown = signedIn.user != null,
            )
            // The server is often not reachable yet right after the TV started: tried for a while.
            val channels = suspend { ChannelAutostart.untilAnswered { client.channels() } }
            val start = when (request.target) {
                // The task was just brought forward as it was (or a cold start opened Home), unless the viewer
                // was watching a live channel: that channel plays again.
                AutostartTarget.Last ->
                    if (navController.currentBackStackEntry?.destination?.hasRoute(LiveRoute::class) == true) {
                        ChannelStart.Stay // the player is in front again and picks the stream up itself
                    } else {
                        ChannelAutostart.resumeLast(record) { channels()?.channels }
                    }
                AutostartTarget.Tv -> ChannelStart.OpenTv
                AutostartTarget.Channel -> ChannelAutostart.lastChannel(record) { channels()?.let { it.channels to it.lastChannel } }
            }
            when (start) {
                ChannelStart.Stay -> Unit
                ChannelStart.OpenTv -> selectTab(NavTab.Tv)
                is ChannelStart.Play -> {
                    // A player or reader that is open is left first, so two never run together.
                    repeat(MEDIA_SCREENS_DEEP) {
                        val current = navController.currentBackStackEntry?.destination
                        if (current != null && MEDIA_ROUTES.any { current.hasRoute(it) }) navController.popBackStack()
                    }
                    // The TV tab stays under the player: Back from the channel lands on the list.
                    selectTab(NavTab.Tv)
                    audio.pause()
                    navController.navigate(LiveRoute(start.channelId, ChannelFilter.All.key))
                }
            }
        }
    }

    // «Где остановился» knows the viewer was watching a channel while the live player is the page on screen. Leaving
    // it in the app (Back, another page) forgets it; the app going to the background with it (standby, the Home
    // key, a power cut) keeps it.
    // Only a move off the player counts: the first page of a cold start must not forget the channel it is to resume.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var wasOnLive by remember { mutableStateOf(false) }
    LaunchedEffect(backStack?.destination) {
        val onLive = backStack?.destination?.hasRoute(LiveRoute::class) == true
        val left = wasOnLive && !onLive
        wasOnLive = onLive
        if (left && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            withContext(Dispatchers.IO) { app.lastChannel.set(null) }
        }
    }

    // A tile of the TV home screen (Watch Next, the «Antresol» channel) or a deep link opened the app: play from where
    // the profile stopped, or open the title's page. Once, while fresh, and only for the profile that is signed in.
    // Collected, not keyed: clearing the request must not cancel the work it started.
    LaunchedEffect(app) {
        app.deepLinkRequested.filterNotNull().collect { request ->
            app.deepLinkRequested.value = null
            if (!request.fresh(SystemClock.elapsedRealtime()) || request.link.userId != state.session.userId) return@collect
            // A player, trailer, live channel or reader that is open is left first, so two never run together.
            repeat(MEDIA_SCREENS_DEEP) {
                val current = navController.currentBackStackEntry?.destination
                if (current != null && MEDIA_ROUTES.any { current.hasRoute(it) }) navController.popBackStack()
            }
            when (val link = request.link) {
                is HomeLink.Play -> play(link.itemId, ResumeMode.Resume)
                is HomeLink.Open -> navController.navigate(DetailRoute(link.kind, link.itemId))
            }
        }
    }

    CompositionLocalProvider(LocalMediaUrls provides client.urls) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AntresolColors.Background)
                // The rest of a held OK after a long press opened a page never presses its buttons.
                .onPreviewKeyEvent(HeldKeyGuard::consume),
        ) {
            NavHost(
                navController = navController,
                startDestination = ShellRootRoute,
                modifier = Modifier.fillMaxSize(),
                // Short fades: the default 700 ms feels slow on a TV and keeps both pages composed.
                enterTransition = { fadeIn(tween(AntresolMotion.FAST_MS)) },
                exitTransition = { fadeOut(tween(AntresolMotion.FAST_MS)) },
                popEnterTransition = { fadeIn(tween(AntresolMotion.FAST_MS)) },
                popExitTransition = { fadeOut(tween(AntresolMotion.FAST_MS)) },
            ) {
                composable<ShellRootRoute> { Box(Modifier.fillMaxSize()) }
                page<HomeRoute>(contentFocus) {
                    HomeScreen(
                        viewModel = viewModel { HomeViewModel(client, app.pendingWrites) },
                        onOpen = open,
                        onPlay = playCard,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = LocalPageCanTakeFocus.current,
                        seeAll = seeAll,
                    )
                }
                page<MoviesRoute>(contentFocus) {
                    LibraryGrid(GridSource(GridKind.Movies), stringResource(R.string.nav_movies), open, onScrolledToTop, LocalPageCanTakeFocus.current)
                }
                page<ShowsRoute>(contentFocus) {
                    LibraryGrid(GridSource(GridKind.Shows), stringResource(R.string.nav_tv_shows), open, onScrolledToTop, LocalPageCanTakeFocus.current)
                }
                page<ChannelsRoute>(contentFocus) {
                    val channelsViewModel = viewModel { ChannelsViewModel(client.channelsApi()) }
                    LaunchedEffect(channelsViewModel) {
                        guideRequests.collect { requested ->
                            if (requested) {
                                guideRequests.value = false
                                channelsViewModel.showView(ChannelsView.Guide)
                            }
                        }
                    }
                    ChannelsScreen(
                        viewModel = channelsViewModel,
                        onWatch = { channel, filter ->
                            // Live TV takes over the screen and the speakers, like a video.
                            audio.pause()
                            navController.navigate(LiveRoute(channel.id, filter.key))
                        },
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = LocalPageCanTakeFocus.current,
                        onPanelOpenChanged = { panelOpen = it },
                    )
                }
                page<MusicRoute>(contentFocus) {
                    ShelvesScreen(
                        title = stringResource(R.string.nav_music),
                        viewModel = viewModel { ShelvesViewModel({ client.musicHome().shelves }, { client.genres().genres }) },
                        onOpen = open,
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = LocalPageCanTakeFocus.current,
                        onGenre = { genre -> openGrid(GridKind.Albums, genre.name) },
                        seeAll = seeAll,
                        actions = { link ->
                            LinkButton(stringResource(R.string.music_all_albums), { openGrid(GridKind.Albums, null) }, Modifier.link("albums"))
                            LinkButton(stringResource(R.string.music_artists), { openGrid(GridKind.Artists, null) }, Modifier.link("artists"))
                            LinkButton(stringResource(R.string.music_songs), { navController.navigate(SongsRoute) }, Modifier.link("songs"))
                            LinkButton(stringResource(R.string.music_playlists), { openGrid(GridKind.Playlists, null) }, Modifier.link("playlists"))
                            PillButton(
                                stringResource(R.string.music_shuffle_all),
                                onClick = { shuffle(ShuffleSource.All) },
                                icon = TvIcons.Shuffle,
                                modifier = Modifier.link("shuffle"),
                            )
                        },
                    )
                }
                page<SearchRoute>(contentFocus) {
                    SearchScreen(
                        viewModel = viewModel { SearchViewModel(client, audio, app.ways) },
                        onOpen = open,
                        onNowPlaying = nowPlaying,
                        onBrowse = { target ->
                            when (target) {
                                BrowseTarget.Movies -> selectTab(NavTab.Movies)
                                BrowseTarget.Shows -> selectTab(NavTab.Shows)
                                BrowseTarget.Music -> selectTab(NavTab.Music)
                            }
                        },
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = LocalPageCanTakeFocus.current,
                    )
                }
                page<SettingsRoute>(contentFocus) {
                    SettingsScreen(
                        state = state,
                        settings = app.settings,
                        onSwitchProfile = { scope.launch { session.switchProfile() } },
                        onSignOut = { scope.launch { session.signOut() } },
                        onScrolledToTop = onScrolledToTop,
                        onMenuOpenChanged = { panelOpen = it },
                        language = uiLanguage,
                        // The «ТВ» tab reads its list again when it is shown (the server orders it by the new choice).
                        channelPrefs = viewModel(key = "channel-prefs") { ChannelPrefsModel(client.channelPrefsApi()) },
                        // A kid's device history is an administrator's to change (the server refuses it too).
                        deviceHistory = if (state.user?.isKid == false) {
                            viewModel(key = "device-history") { DeviceHistoryModel(client.deviceHistoryApi(), session::deviceHistoryChanged) }
                        } else {
                            null
                        },
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
                        canTakeFocus = LocalPageCanTakeFocus.current,
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
                        viewModel = viewModel { DetailViewModel(client, audio, route.kind, route.id, app.pendingWrites, waysSource = app.ways, maxHeight = wayMaxHeight) },
                        audio = audio,
                        onOpen = open,
                        onPlay = play,
                        onPlayWay = playWay,
                        onNowPlaying = nowPlaying,
                        onRead = { volumeId, fromStart -> navController.navigate(ComicReaderRoute(volumeId, fromStart)) },
                        onTrailer = { trailer, name ->
                            // A trailer takes over the screen and the speakers, like a video.
                            audio.pause()
                            navController.navigate(TrailerRoute(trailer.play, trailer.key, name))
                        },
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = LocalPageCanTakeFocus.current,
                        onPanelOpenChanged = { panelOpen = it },
                        onClose = { navController.popBackStack() },
                    )
                }
                page<TitleRoute>(contentFocus) { entry ->
                    val route = entry.toRoute<TitleRoute>()
                    DetailScreen(
                        viewModel = viewModel {
                            DetailViewModel(client, audio, route.kind, 0, app.pendingWrites, tmdbId = route.tmdbId, waysSource = app.ways, maxHeight = wayMaxHeight)
                        },
                        audio = audio,
                        onOpen = open,
                        onPlay = play,
                        onPlayWay = playWay,
                        onNowPlaying = nowPlaying,
                        onRead = { _, _ -> },
                        onTrailer = { _, _ -> },
                        onScrolledToTop = onScrolledToTop,
                        canTakeFocus = LocalPageCanTakeFocus.current,
                        onPanelOpenChanged = { panelOpen = it },
                        onClose = { navController.popBackStack() },
                        // The library has it after all: its own page replaces this one.
                        onOpenLibraryItem = { card ->
                            navController.navigate(DetailRoute(card)) { popUpTo<TitleRoute> { inclusive = true } }
                        },
                    )
                }
                composable<ComicReaderRoute> { entry ->
                    val route = entry.toRoute<ComicReaderRoute>()
                    ComicReaderScreen(
                        viewModel = viewModel { ComicReaderViewModel(client, app.appScope, app.pendingWrites, route.volumeId, route.fromStart) },
                        onOpenVolume = { next ->
                            navController.navigate(ComicReaderRoute(next)) {
                                popUpTo<ComicReaderRoute> { inclusive = true }
                            }
                        },
                        onExit = { navController.popBackStack() },
                    )
                }
                composable<LiveRoute> { entry ->
                    val route = entry.toRoute<LiveRoute>()
                    val owner = LastChannel(state.session.serverId, state.session.userId, state.user?.deviceHistory?.id, route.channelId)
                    LivePlayerScreen(
                        viewModel = viewModel {
                            // The channel tuned in (zapping included) is remembered on the device too, before the server.
                            val base = client.liveApi()
                            val api = object : LiveApi by base {
                                override suspend fun setLast(channelId: Long) {
                                    withContext(Dispatchers.IO) { app.lastChannel.set(owner.copy(channelId = channelId)) }
                                    base.setLast(channelId)
                                }
                            }
                            // «Качество» is the app's default quality setting, shared with films.
                            LivePlayerViewModel(
                                app, client, route.channelId, ChannelFilter.fromKey(route.filter), api = api, platform = LiveTvSession.PLATFORM_ANDROID_TV,
                                quality = app.settings.current.defaultQuality,
                                onQualityChosen = { value -> app.settings.update { it.copy(defaultQuality = value) } },
                            )
                        },
                        onGuide = {
                            navController.popBackStack()
                            guideRequests.value = true
                            selectTab(NavTab.Tv)
                        },
                    )
                }
                composable<TrailerRoute> { entry ->
                    val route = entry.toRoute<TrailerRoute>()
                    if (route.play.isNotBlank()) {
                        TrailerPlayerScreen(
                            viewModel = viewModel { TrailerPlayerViewModel(app, client, route.play, route.name) },
                            onExit = { navController.popBackStack() },
                        )
                    } else {
                        TrailerEmbedScreen(trailerKey = route.key, name = route.name, onExit = { navController.popBackStack() })
                    }
                }
                composable<PlayerRoute> { entry ->
                    val route = entry.toRoute<PlayerRoute>()
                    val way = route.way.takeIf { it.isNotEmpty() }?.let { WayTicket.get(it) }
                    if (route.way.isNotEmpty() && way == null) {
                        // The video of a way outlived its process: there is nothing to play.
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        PlayerScreen(
                            viewModel = viewModel {
                                PlayerViewModel(
                                    app, client, app.appScope, route.itemId, ResumeMode.valueOf(route.resume), app.settings.current, app.pendingWrites,
                                    way = way,
                                )
                            },
                            onExit = { navController.popBackStack() },
                        )
                    }
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
                    photoUrl = client.urls.image(state.session.photo),
                    nowPlaying = audio.currentTrack?.title ?: (audio.content as? AudioContent.Audiobook)?.book?.card?.title,
                    nowPlayingActive = audio.isPlaying,
                    onSelect = selectTab,
                    onNowPlaying = nowPlaying,
                    onSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } },
                    onFocusChanged = { navFocused = it },
                    onEnterContent = contentFocus::enter,
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

/** A tab left for this long is back at its root when the user returns. */
internal const val TAB_RESET_MS = 5_000L

/** After a page's transition, how often and how many times the shell checks that something has focus. */
private const val SETTLE_STEP_MS = 120L
private const val SETTLE_CHECKS = 8

/** Full-screen media that a deep link closes before it opens something else (at most this many are stacked). */
private const val MEDIA_SCREENS_DEEP = 3
private val MEDIA_ROUTES = listOf(PlayerRoute::class, LiveRoute::class, TrailerRoute::class, ComicReaderRoute::class)

/** Whether the current page may take focus now: it is the current entry and the nav does not have focus. */
private val LocalPageCanTakeFocus = staticCompositionLocalOf<() -> Boolean> { { false } }

/**
 * One focus requester per back stack entry. During a page transition both pages are composed; the
 * nav must enter only the current one, never the page that is leaving.
 */
private class PageFocus {
    private val requesters = HashMap<String, FocusRequester>()
    private val memories = HashMap<String, MutableSet<FocusMemory>>()

    /** Entries whose page holds focus now. */
    private val focused = HashSet<String>()

    /** The shell's current back stack entry; read as state, so pages see a change at once. */
    var currentEntry: State<NavBackStackEntry?> = mutableStateOf(null)

    val currentEntryId: String? get() = currentEntry.value?.id

    var navFocused: () -> Boolean = { false }

    var onTabRootBack: (NavTab) -> Unit = {}

    /**
     * Registers the page composed for [entryId]. The same entry can be composed anew while its old
     * composition is still being disposed (a transition reversed by Back), so each composition
     * registers its own objects and [release] only removes them if they are still the current ones.
     */
    fun attach(entryId: String, requester: FocusRequester, pageMemories: MutableSet<FocusMemory>) {
        requesters[entryId] = requester
        memories[entryId] = pageMemories
    }

    fun release(entryId: String, requester: FocusRequester) {
        if (requesters[entryId] !== requester) return
        requesters.remove(entryId)
        memories.remove(entryId)
        focused.remove(entryId)
    }

    /**
     * Down from the nav: focuses the element the current page remembers, else enters the page's
     * focus group (its first element). False when no page can take focus.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun enter(focusManager: FocusManager): Boolean {
        val id = currentEntryId ?: return false
        if (memories[id].orEmpty().any { it.focusRemembered() }) {
            // A remembered group (sort pills) enters its own element.
            focusManager.moveFocus(FocusDirection.Enter)
            return true
        }
        val requester = requesters[id] ?: return false
        return try {
            requester.requestFocus()
            focusManager.moveFocus(FocusDirection.Enter)
            true
        } catch (_: IllegalStateException) {
            // Not attached yet.
            false
        }
    }

    fun canTakeFocus(entryId: String): Boolean = entryId == currentEntryId && !navFocused()

    fun onFocusChanged(entryId: String, hasFocus: Boolean) {
        if (hasFocus) focused += entryId else focused -= entryId
        if (!hasFocus && entryId == currentEntryId) focusLost++
    }

    /** Counts the times the current page lost focus (to anything: the nav, a player, or nothing). */
    var focusLost by mutableIntStateOf(0)
        private set

    /**
     * Puts focus on the current page (its remembered element) when nothing on it has focus. A page
     * with nothing focusable yet takes focus itself when it loads.
     */
    fun ensureFocus(focusManager: FocusManager) {
        val id = currentEntryId ?: return
        if (id in focused) return
        enter(focusManager)
    }
}

/**
 * A destination whose page can be entered from the nav with Down. The page spans the whole screen
 * behind the nav, so geometric focus search never finds it below the pills; the nav instead asks
 * [PageFocus.enter], which focuses the element the page's [FocusMemory] remembers.
 *
 * The page is a plain focus group, not a `focusRestorer()`: the restorer kept the last focused
 * child of the page and redirected later focus requests inside the page to it, even when that
 * child was gone, so a page coming back (Back from a detail) or opening a side panel ended up with
 * no element focused.
 */
@OptIn(ExperimentalComposeUiApi::class)
private inline fun <reified T : Any> NavGraphBuilder.page(
    contentFocus: PageFocus,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        val requester = remember { FocusRequester() }
        val pageMemories = remember { LinkedHashSet<FocusMemory>() }
        // Registered during composition, so the nav can enter the page from its first frame.
        contentFocus.attach(entry.id, requester, pageMemories)
        DisposableEffect(entry.id) { onDispose { contentFocus.release(entry.id, requester) } }
        val gate = remember(entry.id) { { contentFocus.canTakeFocus(entry.id) } }
        // A tab's own page directly on the root: Back there is the shell's (see [PageFocus.onTabRootBack]).
        val tab = NavTab.entries.firstOrNull { entry.destination.hasRoute(it.route::class) }
        BackHandler(enabled = tab != null && contentFocus.currentEntryId == entry.id) { tab?.let(contentFocus.onTabRootBack) }
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(requester)
                .focusGroup()
                .onFocusChanged {
                    // The page's group itself can end up focused with no element in it focused,
                    // which looks the same as no focus at all to the user.
                    contentFocus.onFocusChanged(entry.id, it.hasFocus && !it.isFocused)
                },
        ) {
            CompositionLocalProvider(
                LocalPageCanTakeFocus provides gate,
                LocalFocusMemories provides pageMemories,
            ) {
                content(entry)
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
    GridKind.Albums -> R.string.music_all_albums
    GridKind.Artists -> R.string.music_artists
    GridKind.Playlists -> R.string.music_playlists
}

/** Server shelf ids (Home, Music) and the grid that lists all of it. */
private val SHELF_GRIDS = mapOf(
    "recent-movies" to GridKind.Movies,
    "recent-series" to GridKind.Shows,
    "recent-albums" to GridKind.Albums,
    "artists" to GridKind.Artists,
    "playlists" to GridKind.Playlists,
)

/** Switches tabs the TV way: one entry per tab on the root, each keeping its own scroll and focus state. */
private fun NavHostController.navigateToTab(tab: NavTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** The tab whose stack is showing: the nearest tab page below the top of the back stack. */
private fun NavHostController.currentTab(): NavTab? =
    currentBackStack.value.asReversed().firstNotNullOfOrNull { entry ->
        NavTab.entries.firstOrNull { tab -> entry.destination.hasRoute(tab.route::class) }
    }

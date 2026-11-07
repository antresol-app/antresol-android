package app.antresol.mobile.ui.shell

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import app.antresol.core.model.ShelfId
import app.antresol.core.playback.Resume
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.mobile.AntresolMobileApp
import app.antresol.mobile.MainActivity
import app.antresol.mobile.R as MobileR
import app.antresol.mobile.player.VideoPlayerActivity
import app.antresol.mobile.reader.ComicReaderScreen
import app.antresol.mobile.reader.ComicReaderViewModel
import app.antresol.mobile.reader.PdfReaderScreen
import app.antresol.mobile.reader.PdfReaderViewModel
import app.antresol.mobile.settings.AppSettings
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
import app.antresol.mobile.ui.detail.BookAction
import app.antresol.mobile.ui.detail.ItemDetailViewModel
import app.antresol.mobile.ui.detail.MediaDetailScreen
import app.antresol.mobile.ui.detail.MediaDetailViewModel
import app.antresol.mobile.ui.detail.VideoDetailScreen
import app.antresol.mobile.ui.home.HomeContent
import app.antresol.mobile.ui.home.HomeScreen
import app.antresol.mobile.ui.music.ShuffleAllViewModel
import app.antresol.mobile.ui.music.SongsScreen
import app.antresol.mobile.ui.music.SongsViewModel
import app.antresol.mobile.ui.nowplaying.MiniPlayer
import app.antresol.mobile.ui.nowplaying.MiniPlayerSpace
import app.antresol.mobile.ui.nowplaying.NowPlayingScreen
import app.antresol.mobile.ui.search.SearchScreen
import app.antresol.mobile.ui.search.SearchViewModel
import app.antresol.mobile.ui.settings.SettingsScreen
import app.antresol.mobile.ui.settings.SettingsViewModel
import app.antresol.ui.R
import app.antresol.ui.playback.ResumeMode
import app.antresol.ui.theme.AntresolColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val VIDEO_KINDS = setOf(Kind.MOVIE, Kind.SERIES, Kind.SEASON, Kind.EPISODE)

/** Session actions that ask first: one mis-tap must not revoke the token or drop the server. */
private enum class SessionAction { SignOut, ChangeServer }

/**
 * The signed-in app: a NavHost of tab roots and pushed pages under the floating tab bar. Each tab
 * keeps its own back stack (save/restore state); tapping the current tab pops back to its root.
 * The tab header's avatar opens the account sheet.
 */
@Composable
fun MainShell(session: SessionManager, state: AuthState.SignedIn) {
    // The shell's own ViewModel store: its back stack ViewModels (a reader's final save, an open
    // PDF) are cleared when the profile changes or the session ends. It lives in an Activity-retained
    // holder, so a configuration change that recreates the Activity keeps page state.
    val stores: ShellStores = viewModel()
    val key = state.session.serverId to state.session.userId
    val owner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore = stores.storeFor(key)
        }
    }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(owner) {
        onDispose { if (activity?.isChangingConfigurations != true) stores.release(key) }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
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
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<SessionAction?>(null) }
    var currentTab by rememberSaveable { mutableStateOf(NavTab.Home) }

    val backStack by navController.currentBackStackEntryAsState()
    LaunchedEffect(backStack) {
        val destination = backStack?.destination ?: return@LaunchedEffect
        NavTab.entries.firstOrNull { destination.hasRoute(it.route::class) }?.let { currentTab = it }
    }

    val app = context.applicationContext as AntresolMobileApp
    val playback = app.playback
    // Session changes run in the app scope: a rotation must not cancel a sign-out halfway.
    val switchProfile: () -> Unit = { app.appScope.launch { session.switchProfile() } }
    val runSessionAction: (SessionAction) -> Unit = { action ->
        app.appScope.launch {
            when (action) {
                SessionAction.SignOut -> session.signOut()
                SessionAction.ChangeServer -> session.changeServer()
            }
        }
    }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(playback.nowPlayingRequested) {
        if (playback.nowPlayingRequested) {
            playback.nowPlayingRequested = false
            if (playback.current != null) showNowPlaying = true
        }
    }
    NotificationPermission(app.settings, asked = playback.current != null)

    val open: (Card) -> Unit = { card -> navController.navigate(DetailRoute(card)) }
    val playVideo: (Long, ResumeMode) -> Unit = { id, mode -> VideoPlayerActivity.start(context, id, mode) }
    val read: (BookAction) -> Unit = { action ->
        when (action) {
            is BookAction.ReadComic -> navController.navigate(ComicReaderRoute(action.volumeId, action.fromStart))
            is BookAction.ReadPdf -> navController.navigate(PdfReaderRoute(action.bookId, action.fromStart))
            else -> Unit
        }
    }
    // Continue Watching: a tap carries on (video, audiobook, reader); a long press opens the page.
    val continueCard: (Card) -> Unit = { card ->
        when (card.kind) {
            in VIDEO_KINDS -> playVideo(card.id, ResumeMode.Resume)
            Kind.AUDIOBOOK, Kind.BOOK, Kind.COMIC, Kind.COMIC_VOLUME -> scope.launch {
                val book = try {
                    client.book(card.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                when (val action = book?.let { BookAction.of(it) }) {
                    is BookAction.Listen -> {
                        playback.resumeAudiobook(book, Resume.audiobookStartMs(book.progress, book.durationMs))
                        showNowPlaying = true
                    }
                    is BookAction.ReadComic, is BookAction.ReadPdf -> read(action)
                    is BookAction.Unsupported, null -> open(card)
                }
            }
            else -> open(card)
        }
    }
    val openGrid: OpenGrid = { source, title -> navController.navigate(GridRoute(source.encode(), title)) }
    val selectTab: (NavTab) -> Unit = { tab -> navController.selectTab(tab, reselect = tab == currentTab) }
    val back: () -> Unit = { navController.popBackStack() }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val fullScreen = backStack?.destination?.let { it.hasRoute(ComicReaderRoute::class) || it.hasRoute(PdfReaderRoute::class) } == true
    val miniPlayerSpace = if (playback.current != null) MiniPlayerSpace else 0.dp
    val user = state.user
    CompositionLocalProvider(
        LocalMediaUrls provides client.urls,
        LocalBottomBarPadding provides PaddingValues(bottom = navBottom + BottomBarSpace + miniPlayerSpace),
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
                        viewModel = viewModel { LoadViewModel(pendingWrites = app.pendingWrites) { HomeContent.from(client.home()) } },
                        onOpen = open,
                        onContinue = continueCard,
                        // Videos play (resuming); a hero album or book opens its page.
                        onPlay = { card -> if (card.kind in VIDEO_KINDS) playVideo(card.id, ResumeMode.Resume) else open(card) },
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
                        onSongs = { title -> navController.navigate(SongsRoute(title)) },
                        shuffleAll = viewModel { ShuffleAllViewModel(client) },
                        onPlayTracks = { tracks -> playback.playTracks(tracks) },
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
                        playback = playback,
                        onOpen = open,
                        onTab = selectTab,
                        onGrid = openGrid,
                        onNowPlaying = { showNowPlaying = true },
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
                            viewModel = viewModel { ItemDetailViewModel(client, route.id, app.pendingWrites) },
                            onOpen = open,
                            onBack = back,
                            onPlay = playVideo,
                        )
                    } else {
                        MediaDetailScreen(
                            viewModel = viewModel { MediaDetailViewModel(client, route.kind, route.id, app.pendingWrites) },
                            playback = playback,
                            onOpen = open,
                            onBack = back,
                            onRead = read,
                        )
                    }
                }
                composable<SongsRoute> { entry ->
                    SongsScreen(
                        viewModel = viewModel { SongsViewModel(client, starred = false) },
                        title = entry.toRoute<SongsRoute>().title,
                        playback = playback,
                        onBack = back,
                    )
                }
                composable<ComicReaderRoute> { entry ->
                    val route = entry.toRoute<ComicReaderRoute>()
                    ComicReaderScreen(
                        viewModel = viewModel {
                            ComicReaderViewModel(client.pinned(), app.appScope, app.pendingWrites, route.volumeId, route.fromStart)
                        },
                        onBack = back,
                        onOpenVolume = { next ->
                            navController.navigate(ComicReaderRoute(next)) {
                                popUpTo<ComicReaderRoute> { inclusive = true }
                            }
                        },
                    )
                }
                composable<PdfReaderRoute> { entry ->
                    val route = entry.toRoute<PdfReaderRoute>()
                    PdfReaderScreen(
                        viewModel = viewModel {
                            PdfReaderViewModel(client.pinned(), app.appScope, app.pendingWrites, context.cacheDir, route.bookId, route.fromStart)
                        },
                        onBack = back,
                    )
                }
                composable<SettingsRoute> {
                    val activity = LocalContext.current as? ComponentActivity
                    SettingsScreen(
                        viewModel = viewModel { SettingsViewModel(client) },
                        settings = app.settings,
                        session = state.session,
                        user = user,
                        onBack = back,
                        onSwitchProfile = switchProfile,
                        onSignOut = { confirm = SessionAction.SignOut },
                        onChangeServer = { confirm = SessionAction.ChangeServer },
                        onLanguageChanged = { activity?.let(MainActivity::restart) },
                    )
                }
            }
            if (!fullScreen) {
                // Edge to edge (enforced from API 35): scrolled titles must not collide with the status bar icons.
                val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(statusTop + 16.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to AntresolColors.Background.copy(alpha = 0.8f),
                                0.6f to AntresolColors.Background.copy(alpha = 0.55f),
                                1f to Color.Transparent,
                            ),
                        ),
                )
                // Content fades out under the floating bars, as the glass has no backdrop blur on Android.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to AntresolColors.Background.copy(alpha = 0.85f))),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MiniPlayer(playback, onOpen = { showNowPlaying = true }, modifier = Modifier.padding(bottom = 8.dp))
                    BottomBar(selected = currentTab, onSelect = selectTab)
                }
            }
            AnimatedVisibility(
                visible = showNowPlaying && playback.current != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                NowPlayingScreen(playback, client, onClose = { showNowPlaying = false })
            }
        }
        if (showAccount) {
            AccountSheet(
                session = state.session,
                user = user,
                onDismiss = { showAccount = false },
                onSwitchProfile = {
                    showAccount = false
                    switchProfile()
                },
                onSignOut = {
                    showAccount = false
                    confirm = SessionAction.SignOut
                },
                onChangeServer = {
                    showAccount = false
                    confirm = SessionAction.ChangeServer
                },
                onSettings = {
                    showAccount = false
                    navController.navigate(SettingsRoute)
                },
            )
        }
        confirm?.let { action ->
            SessionActionDialog(
                action = action,
                onConfirm = {
                    confirm = null
                    runSessionAction(action)
                },
                onDismiss = { confirm = null },
            )
        }
    }
}

/** Confirms Sign Out or Change Server; Cancel is the safe default. */
@Composable
private fun SessionActionDialog(action: SessionAction, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, body, confirmLabel) = when (action) {
        SessionAction.SignOut -> Triple(MobileR.string.confirm_sign_out_title, MobileR.string.confirm_sign_out_body, R.string.profile_sign_out)
        SessionAction.ChangeServer -> Triple(MobileR.string.confirm_change_server_title, MobileR.string.confirm_change_server_body, R.string.server_change)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AntresolColors.BackgroundElevated,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(confirmLabel), color = AntresolColors.Red) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Asks once (Android 13+) for the notification permission the media controls need, when something first plays. */
@Composable
private fun NotificationPermission(settings: AppSettings, asked: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(asked) {
        if (!asked || settings.notificationsAsked) return@LaunchedEffect
        settings.markNotificationsAsked()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

package app.antresol.mobile.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.antresol.core.channels.Channels
import app.antresol.core.channels.LiveEdge
import app.antresol.core.channels.LiveStream
import app.antresol.core.channels.LiveTvState
import app.antresol.core.channels.MiniGuide
import app.antresol.core.channels.liveAudioEntries
import app.antresol.ui.playback.liveQualityMenu
import app.antresol.core.model.Channel
import app.antresol.mobile.R as MobileR
import app.antresol.mobile.ui.channels.ChannelRow
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.RoundIconButton
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelLogo
import app.antresol.ui.channels.LiveBadge
import app.antresol.ui.channels.channelFilterTitle
import app.antresol.ui.channels.programClock
import app.antresol.ui.channels.rememberNowMs
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.playback.LivePlayerViewModel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val CONTROLS_HIDE_MS = LiveEdge.CONTROLS_HIDE_MS
private const val GUIDE_HIDE_MS = 10_000L

/** A swipe at least this long switches the channel (vertical) or opens / closes the side guide (horizontal). */
private val SWIPE = 72.dp

/** Width of the side guide over the full-screen picture. */
private val SideGuideWidth = 340.dp

/**
 * Back on the live player closes what is open first: the audio/subtitle sheet, then the side guide,
 * then full screen. With none of them the handler is off and Back is the system's: it leaves the
 * player, with the system's back preview.
 */
@Composable
internal fun LiveBackHandler(
    guideOpen: Boolean,
    menuOpen: Boolean,
    onCloseMenu: () -> Unit,
    onCloseGuide: () -> Unit,
    fullscreen: Boolean = false,
    onExitFullscreen: () -> Unit = {},
) {
    BackHandler(enabled = menuOpen || guideOpen || fullscreen) {
        when {
            menuOpen -> onCloseMenu()
            guideOpen -> onCloseGuide()
            else -> onExitFullscreen()
        }
    }
}

/**
 * A channel live, as video apps play one on a phone (variant A, with
 * C's side guide in landscape).
 *
 * Portrait: the picture 16:9 on top; under it the channel («2 · Documentaries ● LIVE», the star),
 * the programme on air with its times and progress, what is next, then the list's channels with the
 * one on highlighted (a tap switches). Landscape (turning the phone or the full-screen button) is
 * the picture alone, immersive; the list button or a swipe from the left brings the channel guide
 * in over it from the left.
 *
 * On the picture, in both: controls that a tap shows and that hide by themselves — close; audio &
 * subtitles (one sheet), picture-in-picture; play/pause in the centre; at the bottom «LIVE» at the
 * live edge or «К эфиру» behind it, the programme's start and end around its progress, full screen.
 * A vertical swipe on the picture switches channels inside the list it was opened from. Loading,
 * «Запуск канала…» and errors are centred in the picture only. In picture-in-picture only the
 * picture shows. Live: nothing is sent to the server; a pause resumes from the stopped picture while
 * the stream holds it ([LiveEdge]).
 */
@Composable
fun LivePlayerScreen(
    viewModel: LivePlayerViewModel,
    inPictureInPicture: Boolean,
    canPictureInPicture: Boolean,
    fullscreen: Boolean,
    onPictureInPicture: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onExit: () -> Unit,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    val state = viewModel.state
    val stream = state.stream
    var controls by remember { mutableStateOf(true) }
    var guide by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(false) }
    var touches by remember { mutableIntStateOf(0) }
    // The side guide is a full-screen thing: in portrait the list is under the picture.
    LaunchedEffect(fullscreen) { if (!fullscreen) guide = false }
    LiveBackHandler(
        guideOpen = guide,
        menuOpen = tracks || quality,
        onCloseMenu = { tracks = false; quality = false },
        onCloseGuide = { guide = false },
        fullscreen = fullscreen && !inPictureInPicture,
        onExitFullscreen = onToggleFullscreen,
    )
    LaunchedEffect(controls, guide, touches, viewModel.isPlaying, state.channelId, tracks, quality) {
        if (tracks || quality || !viewModel.isPlaying) return@LaunchedEffect
        if (guide) {
            delay(GUIDE_HIDE_MS)
            guide = false
        } else if (controls) {
            delay(CONTROLS_HIDE_MS)
            controls = false
        }
    }
    val zap: (Int) -> Unit = { step ->
        if (viewModel.zap(step)) {
            controls = true
            touches++
        }
    }
    val tune: (Channel) -> Unit = { channel ->
        if (viewModel.tune(channel.id)) controls = true
        touches++
    }
    val holding = viewModel.awaitingPicture && viewModel.hasPicture
    // A channel that could not be opened never shows the previous channel's frame behind its message.
    val failedHere = viewModel.awaitingPicture && stream is LiveStream.Failed
    val scrim by animateFloatAsState(
        when {
            failedHere -> 1f
            holding -> 0.45f
            else -> 0f
        },
        tween(AntresolMotion.MEDIUM_MS),
        label = "held-frame",
    )
    val overlays = !inPictureInPicture
    val canTracks = viewModel.audioOptions.isNotEmpty() || viewModel.subtitleOptions.isNotEmpty()
    val canQuality = viewModel.qualityOptions.isNotEmpty()
    val density = LocalDensity.current

    // Outside the Column's scope, so the overlays inside are the Box's (not ColumnScope's) AnimatedVisibility.
    val picture: @Composable (Modifier) -> Unit = { size ->
        Box(
            size
                .fillMaxWidth()
                .background(Color.Black)
                .testTag("live-surface")
                .pointerInput(fullscreen) {
                    detectTapGestures(onTap = {
                        if (guide) guide = false else controls = !controls
                        touches++
                    })
                }
                .pointerInput(fullscreen) {
                    var dx = 0f
                    var dy = 0f
                    val swipe = with(density) { SWIPE.toPx() }
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDrag = { _, drag -> dx += drag.x; dy += drag.y },
                        onDragEnd = {
                            when {
                                abs(dy) >= abs(dx) && dy <= -swipe -> zap(1)
                                abs(dy) >= abs(dx) && dy >= swipe -> zap(-1)
                                fullscreen && dx >= swipe && state.listed.size > 1 -> { guide = true; touches++ }
                                dx <= -swipe && guide -> guide = false
                            }
                        },
                    )
                },
        ) {
            VideoSurface(viewModel.player, keepLastFrame = true)
            Box(Modifier.fillMaxSize().alpha(scrim).background(Color.Black))
            val ready = stream is LiveStream.Ready && !viewModel.awaitingPicture
            AnimatedVisibility(
                visible = overlays && !guide && (controls || viewModel.paused),
                enter = fadeIn(tween(AntresolMotion.MEDIUM_MS)),
                exit = fadeOut(tween(AntresolMotion.MEDIUM_MS)),
                modifier = Modifier.fillMaxSize(),
            ) {
                LiveControls(
                    state = state,
                    ui = LiveControlsUi(
                        fullscreen = fullscreen,
                        paused = viewModel.paused,
                        centre = ready,
                        behindLive = viewModel.behindLive,
                        tracks = canTracks,
                        quality = canQuality,
                        channels = state.listed.size > 1,
                        pictureInPicture = canPictureInPicture,
                    ),
                    onClose = onExit,
                    onTracks = { tracks = true; touches++ },
                    onGuide = { guide = true; touches++ },
                    onPictureInPicture = onPictureInPicture,
                    onPlayPause = { viewModel.togglePause(); touches++ },
                    onGoLive = { viewModel.goLive(); touches++ },
                    onFavorite = { viewModel.toggleFavorite(); touches++ },
                    onFullscreen = { onToggleFullscreen(); touches++ },
                    onQuality = { quality = true; touches++ },
                )
            }
            if (overlays) {
                LiveStatus(stream, viewModel, compact = !fullscreen, onExit = onExit, modifier = Modifier.align(Alignment.Center))
            }
            AnimatedVisibility(
                visible = overlays && fullscreen && guide,
                enter = slideInHorizontally(tween(AntresolMotion.MEDIUM_MS)) { -it } + fadeIn(tween(AntresolMotion.MEDIUM_MS)),
                exit = slideOutHorizontally(tween(AntresolMotion.MEDIUM_MS)) { -it } + fadeOut(tween(AntresolMotion.MEDIUM_MS)),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                SideGuide(
                    guide = viewModel.miniGuide,
                    title = channelFilterTitle(state.filter, state.categories),
                    currentId = state.channelId,
                    onScroll = { touches++ },
                    onClose = { guide = false },
                    onTune = tune,
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars)),
    ) {
        // The picture: the first child in both layouts, so the video view survives a rotation.
        picture(if (fullscreen) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f))
        if (!fullscreen) {
            LiveBelow(
                state = state,
                guide = viewModel.miniGuide,
                behindLive = viewModel.behindLive,
                onGoLive = { viewModel.goLive(); touches++ },
                onFavorite = viewModel::toggleFavorite,
                onTune = tune,
                modifier = Modifier.weight(1f),
                path = viewModel.playbackPath,
            )
        }
    }
    if (tracks) LiveTracksSheet(viewModel, onDismiss = { tracks = false })
    // «Качество»: the film player's sheet (Авто / Оригинал / 1080p / 720p / 480p, or the stream's own variants)
    if (quality) {
        val content = liveQualityMenu(viewModel)
        if (content == null) quality = false else PlayerMenuSheet(content, onDismiss = { quality = false })
    }
}

/** Starting, buffering and failure, centred in the picture. */
@Composable
private fun LiveStatus(stream: LiveStream, viewModel: LivePlayerViewModel, compact: Boolean, onExit: () -> Unit, modifier: Modifier) {
    when (stream) {
        is LiveStream.Starting -> Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spinner()
            if (!viewModel.hasPicture || stream.attempt > 0) {
                Text(stringResource(R.string.tv_starting), style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary))
            }
        }
        is LiveStream.Ready -> if (viewModel.awaitingPicture || (viewModel.isBuffering && !viewModel.paused)) {
            Box(modifier) { Spinner() }
        }
        is LiveStream.Failed -> FailedPanel(stream.message, stream.external, compact, onRetry = viewModel::retry, onBack = onExit, modifier = modifier)
    }
}

/** What the controls on the picture offer. */
internal data class LiveControlsUi(
    val fullscreen: Boolean,
    val paused: Boolean,
    /** The centre play/pause is shown (the stream has a picture). */
    val centre: Boolean,
    val behindLive: Boolean,
    val tracks: Boolean,
    val channels: Boolean,
    /** «Качество» is offered (always on our channels; on an external one when its stream has several variants). */
    val quality: Boolean = false,
    val pictureInPicture: Boolean,
)

/**
 * The controls over the picture: close on the left (in full screen with the channel and the
 * programme beside it); on the right the channel guide (full screen), the star (full screen), audio
 * & subtitles and picture-in-picture; play/pause in the centre; at the bottom «LIVE» or «К эфиру»,
 * the programme's start and end around its progress, and full screen on/off.
 */
@Composable
internal fun LiveControls(
    state: LiveTvState,
    ui: LiveControlsUi,
    onClose: () -> Unit,
    onTracks: () -> Unit,
    onGuide: () -> Unit,
    onPictureInPicture: () -> Unit,
    onPlayPause: () -> Unit,
    onGoLive: () -> Unit,
    onFavorite: () -> Unit,
    onFullscreen: () -> Unit,
    onQuality: () -> Unit = {},
) {
    val side = if (ui.fullscreen) 20.dp else 10.dp
    val button = if (ui.fullscreen) 44.dp else 40.dp
    val insets = if (ui.fullscreen) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)) else Modifier
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(if (ui.fullscreen) 120.dp else 72.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(if (ui.fullscreen) 140.dp else 80.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().then(insets).padding(horizontal = side, vertical = if (ui.fullscreen) 14.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundIconButton(MobileIcons.Close, stringResource(MobileR.string.player_close), onClose, glass = true, size = button, iconSize = button / 2, modifier = Modifier.testTag("live-close"))
            if (ui.fullscreen) {
                val channel = state.channel
                val onAir = state.onAir(rememberNowMs(5_000L)).first
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    if (channel != null) {
                        Text(
                            stringResource(R.string.tv_channel_number, channel.number, channel.name),
                            style = Antresol.typography.caption.copy(color = AntresolColors.LabelSecondary, fontFeatureSettings = "tnum"),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(onAir?.let(Channels::programLine) ?: stringResource(R.string.tv_nothing), style = Antresol.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (ui.channels) {
                    RoundIconButton(AntresolIcons.List, stringResource(R.string.tv_channel_list), onGuide, glass = true, size = button, iconSize = button / 2, modifier = Modifier.testTag("live-guide"))
                }
                state.channel?.let { channel ->
                    RoundIconButton(
                        if (channel.favorite) AntresolIcons.StarFilled else AntresolIcons.Star,
                        stringResource(if (channel.favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add),
                        onFavorite, glass = true, size = button, iconSize = button / 2,
                        tint = if (channel.favorite) AntresolColors.Orange else AntresolColors.Label,
                        modifier = Modifier.testTag("live-favorite"),
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (ui.tracks) {
                RoundIconButton(AntresolIcons.Subtitles, stringResource(MobileR.string.player_audio_subtitles), onTracks, glass = true, size = button, iconSize = button / 2, modifier = Modifier.testTag("live-tracks"))
            }
            if (ui.quality) {
                RoundIconButton(AntresolIcons.Sliders, stringResource(R.string.detail_quality), onQuality, glass = true, size = button, iconSize = button / 2, modifier = Modifier.testTag("live-quality"))
            }
            if (ui.pictureInPicture) {
                RoundIconButton(MobileIcons.Pip, stringResource(MobileR.string.player_pip), onPictureInPicture, glass = true, size = button, iconSize = button / 2, modifier = Modifier.testTag("live-pip"))
            }
        }
        if (ui.centre || ui.paused) {
            Box(Modifier.align(Alignment.Center)) {
                LivePlayPause(paused = ui.paused, onClick = onPlayPause, size = if (ui.fullscreen) 72.dp else 56.dp)
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(insets).padding(horizontal = side, vertical = if (ui.fullscreen) 14.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LiveEdgeMark(ui.behindLive, onGoLive)
            ProgrammeTimes(state, Modifier.weight(1f).testTag("live-progress"))
            RoundIconButton(
                if (ui.fullscreen) MobileIcons.FullscreenExit else MobileIcons.FullscreenEnter,
                stringResource(if (ui.fullscreen) MobileR.string.player_fullscreen_exit else MobileR.string.player_fullscreen),
                onFullscreen, glass = true, size = 36.dp, iconSize = 20.dp,
                modifier = Modifier.testTag("live-fullscreen"),
            )
        }
    }
}

/** The programme on air's start and end around its progress (empty without a programme). */
@Composable
private fun ProgrammeTimes(state: LiveTvState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val nowMs = rememberNowMs(1_000L)
    val onAir = state.onAir(nowMs).first
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onAir != null) {
            Text(programClock(context, onAir.start), style = Antresol.typography.caption, maxLines = 1)
            ProgressBar(fraction = Channels.progress(onAir, nowMs), modifier = Modifier.weight(1f))
            Text(programClock(context, onAir.end), style = Antresol.typography.caption, maxLines = 1)
        }
    }
}

/** The centre play/pause on the picture: the film player's white circle. */
@Composable
internal fun LivePlayPause(paused: Boolean, size: androidx.compose.ui.unit.Dp = 72.dp, onClick: () -> Unit) {
    RoundIconButton(
        icon = if (paused) AntresolIcons.Play else AntresolIcons.Pause,
        contentDescription = stringResource(if (paused) R.string.action_play else R.string.player_pause),
        onClick = onClick,
        size = size,
        iconSize = size * 0.45f,
        filled = true,
        modifier = Modifier.testTag("live-play-pause"),
    )
}

/** «● LIVE» at the live edge; «К эфиру» (a glass pill that jumps back) more than 10 s behind it. */
@Composable
internal fun LiveEdgeMark(behindLive: Boolean, onGoLive: () -> Unit) {
    if (behindLive) {
        GlassPillButton(stringResource(R.string.tv_go_live), onGoLive, icon = MobileIcons.LiveTv, modifier = Modifier.testTag("live-go-live"))
    } else {
        LiveBadge()
    }
}

/**
 * Under the picture in portrait, one scrolling column: the channel and the programme on air
 * ([LiveDetails]), then the list's channels with the one on highlighted (scrolled into view).
 */
@Composable
internal fun LiveBelow(
    state: LiveTvState,
    guide: MiniGuide,
    behindLive: Boolean,
    onGoLive: () -> Unit,
    onFavorite: () -> Unit,
    onTune: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    path: app.antresol.core.channels.LivePlaybackPath? = null,
) {
    val nowMs = rememberNowMs()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(state.channelId, guide.channels.size) {
        // The current row in view (below the details item), not at the very top.
        if (guide.channels.isNotEmpty() && guide.currentIndex > 2) listState.animateScrollToItem(guide.currentIndex - 1)
    }
    CompositionLocalProvider(app.antresol.ui.channels.LocalReserveChannelLogo provides true) {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth().background(AntresolColors.Background).testTag("live-below"),
            contentPadding = PaddingValues(bottom = bottom + 16.dp),
        ) {
            item(key = "details") {
                LiveDetails(state, behindLive, onGoLive, onFavorite, Modifier.padding(horizontal = 20.dp, vertical = 14.dp), path)
                Box(Modifier.fillMaxWidth().height(1.dp).background(AntresolColors.Hairline))
                if (guide.channels.size > 1) {
                    Text(
                        channelFilterTitle(state.filter, state.categories),
                        style = Antresol.typography.sectionTitle,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                    )
                }
            }
            if (guide.channels.size > 1) {
                items(guide.channels, key = { it.id }) { channel ->
                    ChannelRow(channel, nowMs, onClick = { onTune(channel) }, onToggleFavorite = {}, watching = channel.id == state.channelId)
                }
            }
        }
    }
}

/**
 * The channel and its programme under the picture: «2 · Documentaries ● LIVE» with the star, the
 * title, the episode, start and end around the progress, «Далее …».
 */
@Composable
internal fun LiveDetails(
    state: LiveTvState,
    behindLive: Boolean,
    onGoLive: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    path: app.antresol.core.channels.LivePlaybackPath? = null,
) {
    val context = LocalContext.current
    val urls = app.antresol.mobile.ui.LocalMediaUrls.current
    val nowMs = rememberNowMs(1_000L)
    val channel = state.channel
    val (onAir, next) = state.onAir(nowMs)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (channel != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelLogo(urls?.image(channel.logoUrl), height = 24.dp, altUrl = urls?.image(channel.logoAlt), name = channel.name)
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.tv_channel_number, channel.number, channel.name),
                        style = Antresol.typography.title.copy(color = AntresolColors.LabelSecondary, fontFeatureSettings = "tnum"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    LiveEdgeMark(behindLive, onGoLive)
                }
                RoundIconButton(
                    if (channel.favorite) AntresolIcons.StarFilled else AntresolIcons.Star,
                    stringResource(if (channel.favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add),
                    onFavorite, glass = true, size = 40.dp, iconSize = 20.dp,
                    tint = if (channel.favorite) AntresolColors.Orange else AntresolColors.Label,
                    modifier = Modifier.testTag("live-favorite"),
                )
            }
        }
        Text(onAir?.title ?: stringResource(R.string.tv_nothing), style = Antresol.typography.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onAir != null && onAir.subtitle.isNotBlank()) {
            Text(onAir.subtitle, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onAir != null) ProgrammeTimes(state, Modifier.padding(top = 6.dp).fillMaxWidth())
        if (next != null) {
            Text(
                stringResource(R.string.tv_next_short, programClock(context, next.start), Channels.programLine(next)),
                style = Antresol.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (state.noSupportedAudio) {
            Text(
                stringResource(R.string.tv_no_supported_audio),
                style = Antresol.typography.bodyEmphasis,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp).testTag("live-no-audio"),
            )
        }
        // How the stream arrives: «Напрямую · host» / «Через Antresol», its format, codecs and source.
        if (path != null) {
            Text(
                app.antresol.ui.playback.playbackPathText(path),
                style = Antresol.typography.caption.copy(color = AntresolColors.LabelSecondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp).testTag("live-path"),
            )
        }
    }
}

/** The channel guide that comes in from the left over the full-screen picture (TiviMate, Google TV). */
@Composable
internal fun SideGuide(guide: MiniGuide, title: String, currentId: Long, onScroll: () -> Unit, onClose: () -> Unit, onTune: (Channel) -> Unit) {
    val nowMs = rememberNowMs()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (guide.currentIndex - 1).coerceAtLeast(0))
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) onScroll() }
    Column(
        Modifier
            .fillMaxHeight()
            .background(AntresolColors.Background.copy(alpha = 0.94f))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .width(SideGuideWidth)
            .padding(top = 12.dp)
            .testTag("live-side-guide"),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundIconButton(MobileIcons.Close, stringResource(MobileR.string.player_close), onClose, glass = true, size = 36.dp, iconSize = 18.dp)
            Text(title, style = Antresol.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        CompositionLocalProvider(app.antresol.ui.channels.LocalReserveChannelLogo provides true) {
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                items(guide.channels, key = { it.id }) { channel ->
                    ChannelRow(channel, nowMs, onClick = { onTune(channel) }, onToggleFavorite = {}, watching = channel.id == currentId)
                }
            }
        }
    }
}

/** One choice of the track sheet: what it reads, whether it is ticked, what it does. */
internal data class TrackOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

/** A titled group of the track sheet («Звук», «Субтитры»). */
internal data class TrackSection(val title: String, val options: List<TrackOption>)

/**
 * «Звук и субтитры» for the programme on air in one sheet: the audio renditions the server names
 * for it (a cleaned voice is «Очищенный перевод» under its voice), then «Выкл.» and the subtitles;
 * ticked by name, the profile's languages by default; the choice sticks on the channel.
 */
@Composable
private fun LiveTracksSheet(viewModel: LivePlayerViewModel, onDismiss: () -> Unit) {
    val selection = viewModel.trackSelection
    val audio = liveAudioEntries(viewModel.audioOptions, selection.audio?.name).map { e ->
        val label = if (e.isToggle) stringResource(R.string.track_cleaned_toggle) else e.track.label.ifBlank { e.track.name }
        TrackOption(label, e.checked) { viewModel.selectAudio(e.plays) }
    }
    val subtitles = viewModel.subtitleOptions.takeIf { it.isNotEmpty() }?.let { subs ->
        listOf(TrackOption(stringResource(R.string.detail_subtitles_off), selection.subtitles == null) { viewModel.selectSubtitles(null) }) +
            subs.map { track -> TrackOption(track.label.ifBlank { track.name }, track.name == selection.subtitles?.name) { viewModel.selectSubtitles(track) } }
    }.orEmpty()
    LiveTrackSheet(
        sections = listOfNotNull(
            TrackSection(stringResource(R.string.detail_audio), audio).takeIf { audio.isNotEmpty() },
            TrackSection(stringResource(R.string.detail_subtitles), subtitles).takeIf { subtitles.isNotEmpty() },
        ),
        onDismiss = onDismiss,
    )
}

/**
 * The track sheet: its sections and their choices, however many; the list scrolls inside the sheet
 * (a phone in landscape is 360 dp high), the first ticked choice opens in view, and a long name ends
 * in «…» rather than widening the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveTrackSheet(sections: List<TrackSection>, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        val scroll = rememberScrollState()
        var selectedTop by remember { mutableIntStateOf(-1) }
        LaunchedEffect(selectedTop, scroll.viewportSize) {
            if (selectedTop >= 0 && scroll.viewportSize > 0) {
                val row = with(density) { 52.dp.roundToPx() }
                if (selectedTop + row > scroll.value + scroll.viewportSize) scroll.scrollTo(selectedTop + row - scroll.viewportSize + row / 2)
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(bottom = 16.dp).testTag("live-track-list")) {
            var firstTicked = true
            sections.forEach { section ->
                Text(section.title, style = Antresol.typography.sectionTitle, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
                section.options.forEach { option ->
                    val track = option.selected && firstTicked
                    if (option.selected) firstTicked = false
                    Row(
                        modifier = Modifier
                            .then(if (track) Modifier.onGloballyPositioned { selectedTop = it.positionInParent().y.toInt() } else Modifier)
                            .fillMaxWidth()
                            .clickable {
                                option.onSelect()
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                            .testTag("live-track-option"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(22.dp)) {
                            if (option.selected) Icon(AntresolIcons.Check, contentDescription = null, tint = AntresolColors.Accent)
                        }
                        Text(option.label, style = Antresol.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun FailedPanel(message: String?, external: Boolean, compact: Boolean, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val failed = stringResource(if (external) R.string.tv_failed_external else R.string.tv_failed)
    val detail = if (external) stringResource(R.string.tv_failed_external_hint) else message
    Column(
        modifier = modifier
            .padding(12.dp)
            .widthIn(max = 420.dp)
            .background(AntresolColors.Glass, AntresolShapes.sheet)
            .padding(horizontal = if (compact) 20.dp else 28.dp, vertical = if (compact) 12.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        Text(failed, style = if (compact) Antresol.typography.title else Antresol.typography.sectionTitle, textAlign = TextAlign.Center)
        if (detail != null && detail != failed) {
            Text(detail, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), textAlign = TextAlign.Center, maxLines = if (compact) 2 else 4, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryPillButton(stringResource(R.string.action_retry), onClick = onRetry, modifier = Modifier.testTag("live-retry"))
            GlassPillButton(stringResource(R.string.player_back), onClick = onBack)
        }
    }
}

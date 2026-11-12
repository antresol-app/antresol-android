package app.antresol.mobile.player

import app.antresol.ui.channels.ChannelLogo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import app.antresol.mobile.R as MobileR
import app.antresol.ui.components.AntresolIcons
import app.antresol.core.model.ProgramTrack
import app.antresol.core.channels.ChannelFilter
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
import app.antresol.core.model.Channel
import app.antresol.mobile.ui.components.GlassIconButton
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.RoundIconButton
import app.antresol.mobile.ui.channels.ChannelCard
import app.antresol.ui.R
import app.antresol.ui.channels.LiveBadge
import app.antresol.ui.channels.programClock
import app.antresol.ui.channels.rememberNowMs
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.playback.LivePlayerViewModel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.delay

private const val CONTROLS_HIDE_MS = LiveEdge.CONTROLS_HIDE_MS
private const val GUIDE_HIDE_MS = 8_000L

/** A vertical swipe at least this long switches the channel. */
private val ZAP_SWIPE = 72.dp

/** Width of a channel card in the strip. */
private val StripCardWidth = 168.dp

/**
 * What is over the picture: nothing; the info bar alone (after a switch); the controls (a tap: the
 * top bar with back, favourite, audio, subtitles, channel list and PiP, channel up/down and the
 * info bar); or the channel strip.
 */
private enum class Overlay { Hidden, Banner, Controls, Guide }

/**
 * Back on the live player closes what is open over the picture first: the audio/subtitle sheet,
 * then the channel strip; only with neither open does it leave the player.
 */
@Composable
internal fun LiveBackHandler(
    guideOpen: Boolean,
    menuOpen: Boolean,
    onCloseMenu: () -> Unit,
    onCloseGuide: () -> Unit,
    onExit: () -> Unit,
) {
    BackHandler {
        when {
            menuOpen -> onCloseMenu()
            guideOpen -> onCloseGuide()
            else -> onExit()
        }
    }
}

/** The track picker open over the player. */
private enum class TrackMenu { Audio, Subtitles }

/**
 * A channel live, edge to edge, in the style of the app's film player. A tap shows the controls;
 * another tap hides them. A vertical swipe switches channels inside the list the player was opened
 * from (up for the next number); the info bar slides in, and the previous channel's last frame
 * holds, dimmed, until the new one draws. Audio and subtitles are the stream's renditions, picked
 * per programme; the choice sticks on the channel. In picture-in-picture only the picture shows.
 * It is live: no seek bar and nothing sent to the server, but it may be paused — the centre
 * play/pause comes with the controls (and stays while paused); a resume continues from the stopped
 * picture while the stream holds it, else at the live edge; more than 10 s behind the edge «В эфир»
 * replaces «Прямой эфир» in the info bar and jumps back ([LiveEdge]).
 */
@Composable
fun LivePlayerScreen(
    viewModel: LivePlayerViewModel,
    inPictureInPicture: Boolean,
    canPictureInPicture: Boolean,
    onPictureInPicture: () -> Unit,
    onExit: () -> Unit,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    val state = viewModel.state
    val stream = state.stream
    var overlay by remember { mutableStateOf(Overlay.Banner) }
    var menu by remember { mutableStateOf<TrackMenu?>(null) }
    LiveBackHandler(
        guideOpen = overlay == Overlay.Guide,
        menuOpen = menu != null,
        onCloseMenu = { menu = null },
        onCloseGuide = { overlay = Overlay.Hidden },
        onExit = onExit,
    )
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(overlay, touches, viewModel.isPlaying, state.channelId, menu) {
        if (overlay != Overlay.Hidden && viewModel.isPlaying && menu == null) {
            delay(if (overlay == Overlay.Guide) GUIDE_HIDE_MS else CONTROLS_HIDE_MS)
            overlay = Overlay.Hidden
        }
    }
    val zap: (Int) -> Unit = { step ->
        if (viewModel.zap(step)) {
            if (overlay == Overlay.Hidden || overlay == Overlay.Guide) overlay = Overlay.Banner
            touches++
        }
    }
    val holding = viewModel.awaitingPicture && viewModel.hasPicture
    // A channel that could not be opened never shows the previous channel's frame behind its message.
    val failedHere = viewModel.awaitingPicture && viewModel.state.stream is LiveStream.Failed
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
    val controls = overlays && overlay == Overlay.Controls
    val infoShown = overlays && (overlay == Overlay.Banner || overlay == Overlay.Controls || (!viewModel.isPlaying && overlay != Overlay.Guide))

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("live-surface")
            .pointerInput(Unit) {
                detectTapGestures(onTap = { overlay = if (overlay == Overlay.Controls || overlay == Overlay.Guide) Overlay.Hidden else Overlay.Controls })
            }
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onVerticalDrag = { _, dy -> dragged += dy },
                    onDragEnd = {
                        if (dragged <= -ZAP_SWIPE.toPx()) zap(1) else if (dragged >= ZAP_SWIPE.toPx()) zap(-1)
                    },
                )
            },
    ) {
        VideoSurface(viewModel.player, keepLastFrame = true)
        // The previous channel's last frame, dimmed while the new one starts.
        Box(
            Modifier
                .fillMaxSize()
                .alpha(scrim)
                .background(Color.Black),
        )
        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
                TopBar(
                    viewModel = viewModel,
                    canPictureInPicture = canPictureInPicture,
                    onPictureInPicture = onPictureInPicture,
                    onExit = onExit,
                    onMenu = { menu = it; touches++ },
                    onGuide = { overlay = Overlay.Guide },
                    onTouch = { touches++ },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                if (state.listed.size > 1) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(end = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        RoundIconButton(MobileIcons.ChevronUp, stringResource(R.string.tv_channel_up), { zap(1) }, glass = true, size = 48.dp, iconSize = 24.dp, modifier = Modifier.testTag("live-channel-up"))
                        RoundIconButton(MobileIcons.ChevronDown, stringResource(R.string.tv_channel_down), { zap(-1) }, glass = true, size = 48.dp, iconSize = 24.dp, modifier = Modifier.testTag("live-channel-down"))
                    }
                }
            }
        }
        val ready = stream is LiveStream.Ready && !viewModel.awaitingPicture
        AnimatedVisibility(
            visible = overlays && overlay != Overlay.Guide && LiveEdge.centerShown(controls = controls, paused = viewModel.paused, ready = ready),
            enter = fadeIn(tween(AntresolMotion.MEDIUM_MS)),
            exit = fadeOut(tween(AntresolMotion.MEDIUM_MS)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            LivePlayPause(paused = viewModel.paused, onClick = { viewModel.togglePause(); touches++ })
        }
        AnimatedVisibility(
            visible = infoShown,
            enter = slideInVertically(tween(AntresolMotion.MEDIUM_MS)) { it / 3 } + fadeIn(tween(AntresolMotion.MEDIUM_MS)),
            exit = slideOutVertically(tween(AntresolMotion.MEDIUM_MS)) { it / 4 } + fadeOut(tween(AntresolMotion.MEDIUM_MS)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            InfoBar(state, behindLive = viewModel.behindLive, onGoLive = { viewModel.goLive(); touches++ })
        }
        AnimatedVisibility(
            visible = overlays && overlay == Overlay.Guide,
            enter = slideInVertically(tween(AntresolMotion.MEDIUM_MS)) { it / 2 } + fadeIn(tween(AntresolMotion.MEDIUM_MS)),
            exit = slideOutVertically(tween(AntresolMotion.MEDIUM_MS)) { it / 2 } + fadeOut(tween(AntresolMotion.MEDIUM_MS)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ChannelStrip(
                guide = viewModel.miniGuide,
                title = filterTitle(state.filter),
                currentId = state.channelId,
                onScroll = { touches++ },
                onTune = { channel ->
                    if (viewModel.tune(channel.id)) overlay = Overlay.Banner
                    touches++
                },
            )
        }
        when (stream) {
            is LiveStream.Starting -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spinner()
                if (overlays && (!viewModel.hasPicture || stream.attempt > 0)) {
                    Text(stringResource(R.string.tv_starting), style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary))
                }
            }
            is LiveStream.Ready -> if (viewModel.awaitingPicture || (viewModel.isBuffering && !viewModel.paused)) {
                Box(Modifier.align(Alignment.Center)) { Spinner() }
            }
            is LiveStream.Failed -> if (overlays) {
                FailedPanel(stream.message, stream.external, onRetry = viewModel::retry, onBack = onExit, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
    menu?.let { open -> TrackSheet(open, viewModel, onDismiss = { menu = null }) }
}

/** The centre play/pause on the picture: the film player's white circle. */
@Composable
internal fun LivePlayPause(paused: Boolean, onClick: () -> Unit) {
    RoundIconButton(
        icon = if (paused) AntresolIcons.Play else AntresolIcons.Pause,
        contentDescription = stringResource(if (paused) R.string.action_play else R.string.player_pause),
        onClick = onClick,
        size = 76.dp,
        iconSize = 34.dp,
        filled = true,
        modifier = Modifier.testTag("live-play-pause"),
    )
}

/** «● Прямой эфир» at the live edge; «В эфир» (a glass pill that jumps back) more than 10 s behind it. */
@Composable
internal fun LiveEdgeMark(behindLive: Boolean, onGoLive: () -> Unit) {
    if (behindLive) {
        GlassPillButton(stringResource(R.string.tv_go_live), onGoLive, icon = MobileIcons.LiveTv, modifier = Modifier.testTag("live-go-live"))
    } else {
        LiveBadge()
    }
}

/** «Все каналы», «Избранное» or the theme: the list the player zaps through. */
@Composable
private fun filterTitle(filter: ChannelFilter): String = when (filter) {
    ChannelFilter.All -> stringResource(R.string.tv_all_channels)
    ChannelFilter.Favorites -> stringResource(R.string.tv_favorites)
    is ChannelFilter.Theme -> filter.category
}

/** What the player's top bar offers; [favorite] null hides the star (no channel yet). */
internal data class TopBarActions(
    val favorite: Boolean?,
    val audio: Boolean,
    val subtitles: Boolean,
    val channels: Boolean,
    val pictureInPicture: Boolean,
)

/** Below this width the secondary buttons go into «…» (a portrait split screen, a small phone). */
private val TopBarFullWidth = 440.dp

@Composable
private fun TopBar(
    viewModel: LivePlayerViewModel,
    canPictureInPicture: Boolean,
    onPictureInPicture: () -> Unit,
    onExit: () -> Unit,
    onMenu: (TrackMenu) -> Unit,
    onGuide: () -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveTopBar(
        actions = TopBarActions(
            favorite = viewModel.state.channel?.favorite,
            audio = viewModel.audioOptions.isNotEmpty(),
            subtitles = viewModel.subtitleOptions.isNotEmpty(),
            channels = viewModel.state.listed.size > 1,
            pictureInPicture = canPictureInPicture,
        ),
        onExit = onExit,
        onFavorite = { onTouch(); viewModel.toggleFavorite() },
        onAudio = { onMenu(TrackMenu.Audio) },
        onSubtitles = { onMenu(TrackMenu.Subtitles) },
        onGuide = onGuide,
        onPictureInPicture = onPictureInPicture,
        onTouch = onTouch,
        modifier = modifier,
    )
}

/**
 * The top bar: back on the left; favourite, audio, subtitles, channel list and picture-in-picture on
 * the right, inside the safe area. When they don't fit ([TopBarFullWidth]), all but the star go into
 * a «…» menu, so nothing runs off the screen.
 */
@Composable
internal fun LiveTopBar(
    actions: TopBarActions,
    onExit: () -> Unit,
    onFavorite: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onGuide: () -> Unit,
    onPictureInPicture: () -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val compact = maxWidth < TopBarFullWidth
        var more by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassIconButton(MobileIcons.ChevronLeft, stringResource(R.string.tv_back), onExit, modifier = Modifier.testTag("live-back"))
            Spacer(Modifier.weight(1f))
            actions.favorite?.let { favorite ->
                RoundIconButton(
                    if (favorite) AntresolIcons.StarFilled else AntresolIcons.Star,
                    stringResource(if (favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add),
                    onFavorite,
                    glass = true, size = 44.dp, iconSize = 22.dp,
                    modifier = Modifier.testTag("live-favorite"),
                )
            }
            if (!compact) {
                if (actions.audio) {
                    RoundIconButton(AntresolIcons.Audio, stringResource(R.string.detail_audio), onAudio, glass = true, size = 44.dp, iconSize = 22.dp, modifier = Modifier.testTag("live-audio"))
                }
                if (actions.subtitles) {
                    RoundIconButton(AntresolIcons.Subtitles, stringResource(R.string.detail_subtitles), onSubtitles, glass = true, size = 44.dp, iconSize = 22.dp, modifier = Modifier.testTag("live-subtitles"))
                }
                if (actions.channels) {
                    RoundIconButton(AntresolIcons.List, stringResource(R.string.tv_channel_list), onGuide, glass = true, size = 44.dp, iconSize = 22.dp, modifier = Modifier.testTag("live-guide"))
                }
                if (actions.pictureInPicture) {
                    RoundIconButton(MobileIcons.Pip, stringResource(MobileR.string.player_pip), onPictureInPicture, glass = true, size = 44.dp, iconSize = 22.dp, modifier = Modifier.testTag("live-pip"))
                }
            } else if (actions.audio || actions.subtitles || actions.channels || actions.pictureInPicture) {
                Box {
                    RoundIconButton(MobileIcons.More, stringResource(R.string.action_more), { onTouch(); more = true }, glass = true, size = 44.dp, iconSize = 22.dp, modifier = Modifier.testTag("live-more"))
                    DropdownMenu(
                        expanded = more,
                        onDismissRequest = { more = false },
                        containerColor = AntresolColors.BackgroundElevated,
                        shape = AntresolShapes.control,
                    ) {
                        @Composable
                        fun item(label: String, icon: ImageVector, action: () -> Unit) = DropdownMenuItem(
                            text = { Text(label, style = Antresol.typography.body) },
                            leadingIcon = { Icon(icon, null, tint = AntresolColors.Label, modifier = Modifier.size(20.dp)) },
                            onClick = { more = false; action() },
                        )
                        if (actions.audio) item(stringResource(R.string.detail_audio), AntresolIcons.Audio, onAudio)
                        if (actions.subtitles) item(stringResource(R.string.detail_subtitles), AntresolIcons.Subtitles, onSubtitles)
                        if (actions.channels) item(stringResource(R.string.tv_channel_list), AntresolIcons.List, onGuide)
                        if (actions.pictureInPicture) item(stringResource(MobileR.string.player_pip), MobileIcons.Pip, onPictureInPicture)
                    }
                }
            }
        }
    }
}

/**
 * The info bar at the bottom (Apple TV / Android TV Live Channels): «10 · Comedy ● Live», the
 * programme on air with its episode, its start and end around the live progress, and what is next.
 */
@Composable
internal fun InfoBar(state: LiveTvState, behindLive: Boolean = false, onGoLive: () -> Unit = {}) {
    val context = LocalContext.current
    val urls = app.antresol.mobile.ui.LocalMediaUrls.current
    val nowMs = rememberNowMs(1_000L)
    val channel = state.channel
    val (onAir, next) = state.onAir(nowMs)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.85f)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (channel != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelLogo(urls?.image(channel.logoUrl), height = 26.dp)
                Text(
                    stringResource(R.string.tv_channel_number, channel.number, channel.name),
                    style = Antresol.typography.title.copy(color = AntresolColors.LabelSecondary, fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (channel.favorite) Icon(AntresolIcons.StarFilled, null, tint = AntresolColors.Orange, modifier = Modifier.size(14.dp))
                LiveEdgeMark(behindLive, onGoLive)
            }
        }
        Text(
            onAir?.title ?: stringResource(R.string.tv_nothing),
            style = Antresol.typography.pageTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onAir != null && onAir.subtitle.isNotBlank()) {
            Text(onAir.subtitle, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onAir != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                // The line runs the bar's full width on every screen, as on the TV and the film player.
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().testTag("live-progress"),
            ) {
                Text(programClock(context, onAir.start), style = Antresol.typography.caption)
                ProgressBar(fraction = Channels.progress(onAir, nowMs), modifier = Modifier.weight(1f))
                Text(programClock(context, onAir.end), style = Antresol.typography.caption)
            }
        }
        if (next != null) {
            Text(
                stringResource(R.string.tv_next_short, programClock(context, next.start), Channels.programLine(next)),
                style = Antresol.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The list's channels in a row at the bottom, scrolled to the one on; a tap switches to it. */
@Composable
private fun ChannelStrip(guide: MiniGuide, title: String, currentId: Long, onScroll: () -> Unit, onTune: (Channel) -> Unit) {
    val nowMs = rememberNowMs()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = guide.currentIndex)
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) onScroll() }
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal).asPaddingValues()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black.copy(alpha = 0.65f), 1f to Color.Black.copy(alpha = 0.9f)))
            .padding(top = 28.dp, bottom = insets.calculateBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = Antresol.typography.title,
            modifier = Modifier.padding(start = 24.dp + insets.calculateStartPadding(direction)),
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(
                start = 24.dp + insets.calculateStartPadding(direction),
                end = 24.dp + insets.calculateEndPadding(direction),
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(guide.channels, key = { it.id }) { channel ->
                androidx.compose.runtime.CompositionLocalProvider(
                    app.antresol.ui.channels.LocalReserveChannelLogo provides app.antresol.ui.channels.reserveLogoSlot(guide.channels.map { it.logoUrl }),
                ) {
                ChannelCard(
                    channel = channel,
                    nowMs = nowMs,
                    onClick = { onTune(channel) },
                    width = StripCardWidth,
                    watching = channel.id == currentId,
                    showNext = false,
                )
                }
            }
        }
    }
}

/** One choice of the track sheet: what it reads, whether it is ticked, what it does. */
internal data class TrackOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

/**
 * «Звук» / «Субтитры» for the programme on air: the renditions the server names for it (up to 8
 * audio and 6 subtitles, «Русский — LostFilm», «Оригинал (English)»…), ticked by `name`; the
 * choice sticks for this player only.
 */
@Composable
private fun TrackSheet(menu: TrackMenu, viewModel: LivePlayerViewModel, onDismiss: () -> Unit) {
    val selection = viewModel.trackSelection
    val options: List<TrackOption> = when (menu) {
        TrackMenu.Audio -> viewModel.audioOptions.map { track ->
            TrackOption(track.label.ifBlank { track.name }, track.name == selection.audio?.name) { viewModel.selectAudio(track) }
        }
        TrackMenu.Subtitles -> listOf(
            TrackOption(stringResource(R.string.detail_subtitles_off), selection.subtitles == null) { viewModel.selectSubtitles(null) },
        ) + viewModel.subtitleOptions.map { track: ProgramTrack ->
            TrackOption(track.label.ifBlank { track.name }, track.name == selection.subtitles?.name) { viewModel.selectSubtitles(track) }
        }
    }
    LiveTrackSheet(
        title = stringResource(if (menu == TrackMenu.Audio) R.string.detail_audio else R.string.detail_subtitles),
        options = options,
        onDismiss = onDismiss,
    )
}

/**
 * The track sheet: a title and the choices, however many; the list scrolls inside the sheet (a
 * phone in landscape is 360 dp high), and a long name ends in «…» rather than widening the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveTrackSheet(title: String, options: List<TrackOption>, onDismiss: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        val scroll = rememberScrollState()
        // The ticked choice opens in view (the 8th voice is below the fold on a landscape phone).
        var selectedTop by remember { mutableIntStateOf(-1) }
        LaunchedEffect(selectedTop, scroll.viewportSize) {
            if (selectedTop >= 0 && scroll.viewportSize > 0) {
                val row = with(density) { 52.dp.roundToPx() }
                if (selectedTop + row > scroll.value + scroll.viewportSize) scroll.scrollTo(selectedTop + row - scroll.viewportSize + row / 2)
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(bottom = 16.dp).testTag("live-track-list")) {
            Text(
                title,
                style = Antresol.typography.sectionTitle,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .then(if (option.selected) Modifier.onGloballyPositioned { selectedTop = it.positionInParent().y.toInt() } else Modifier)
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

@Composable
private fun FailedPanel(message: String?, external: Boolean, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val failed = stringResource(if (external) R.string.tv_failed_external else R.string.tv_failed)
    val detail = if (external) stringResource(R.string.tv_failed_external_hint) else message
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .background(AntresolColors.Glass, AntresolShapes.sheet)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(failed, style = Antresol.typography.sectionTitle, textAlign = TextAlign.Center)
        if (detail != null && detail != failed) {
            Text(detail, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            PrimaryPillButton(stringResource(R.string.action_retry), onClick = onRetry, modifier = Modifier.testTag("live-retry"))
            GlassPillButton(stringResource(R.string.player_back), onClick = onBack)
        }
    }
}

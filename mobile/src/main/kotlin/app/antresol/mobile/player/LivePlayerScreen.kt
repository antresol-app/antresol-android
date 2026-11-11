package app.antresol.mobile.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.antresol.core.channels.Channels
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
import app.antresol.ui.channels.ChannelLogoBadge
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

private const val BANNER_HIDE_MS = 5_000L
private const val GUIDE_HIDE_MS = 8_000L

/** A vertical swipe at least this long switches the channel. */
private val ZAP_SWIPE = 72.dp

/** Width of a channel card in the strip. */
private val StripCardWidth = 164.dp

/** What is over the picture: nothing, the channel banner (after a switch), or the banner with the strip (a tap). */
private enum class Overlay { Hidden, Banner, Guide }

/**
 * A channel live, edge to edge. A tap shows the banner (back, the channel with «Live», the programme
 * on air with its progress, what is next, channel up/down) and the strip of the section's channels;
 * another tap hides them. A vertical swipe switches channels (up for the next number); the banner
 * slides in, and the previous channel's last frame holds, dimmed, until the new one draws. In
 * picture-in-picture only the picture shows. It is live: no seek bar, no pause.
 */
@Composable
fun LivePlayerScreen(viewModel: LivePlayerViewModel, inPictureInPicture: Boolean, onExit: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    BackHandler(onBack = onExit)
    val state = viewModel.state
    val stream = state.stream
    var overlay by remember { mutableStateOf(Overlay.Banner) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(overlay, touches, viewModel.isPlaying, state.channelId) {
        if (overlay != Overlay.Hidden && viewModel.isPlaying) {
            delay(if (overlay == Overlay.Guide) GUIDE_HIDE_MS else BANNER_HIDE_MS)
            overlay = Overlay.Hidden
        }
    }
    val zap: (Int) -> Unit = { step ->
        if (viewModel.zap(step)) {
            if (overlay == Overlay.Hidden) overlay = Overlay.Banner
            touches++
        }
    }
    val holding = viewModel.awaitingPicture && viewModel.hasPicture
    val scrim by animateFloatAsState(if (holding) 0.45f else 0f, tween(AntresolMotion.MEDIUM_MS), label = "held-frame")
    val overlays = !inPictureInPicture
    val bannerShown = overlays && (overlay != Overlay.Hidden || !viewModel.isPlaying)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("live-surface")
            .pointerInput(Unit) {
                // A tap opens the banner with the strip (also over a switch's banner); a tap on that hides it.
                detectTapGestures(onTap = { overlay = if (overlay == Overlay.Guide) Overlay.Hidden else Overlay.Guide })
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
        AnimatedVisibility(
            visible = bannerShown,
            enter = slideInVertically(tween(AntresolMotion.MEDIUM_MS)) { -it / 3 } + fadeIn(tween(AntresolMotion.MEDIUM_MS)),
            exit = slideOutVertically(tween(AntresolMotion.MEDIUM_MS)) { -it / 4 } + fadeOut(tween(AntresolMotion.MEDIUM_MS)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ChannelBanner(state, onExit = onExit, onZap = { step -> touches++; zap(step) })
        }
        AnimatedVisibility(
            visible = overlays && overlay == Overlay.Guide,
            enter = slideInVertically(tween(AntresolMotion.MEDIUM_MS)) { it / 2 } + fadeIn(tween(AntresolMotion.MEDIUM_MS)),
            exit = slideOutVertically(tween(AntresolMotion.MEDIUM_MS)) { it / 2 } + fadeOut(tween(AntresolMotion.MEDIUM_MS)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ChannelStrip(
                guide = viewModel.miniGuide,
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
            is LiveStream.Ready -> if (viewModel.awaitingPicture || viewModel.isBuffering) {
                Box(Modifier.align(Alignment.Center)) { Spinner() }
            }
            is LiveStream.Failed -> if (overlays) {
                FailedPanel(stream.message, onRetry = viewModel::retry, onBack = onExit, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/**
 * The banner at the top: back, the logo, «10 · Comedy» with «● Live», the programme on air, its
 * start and end around the live progress, what is next, and the channel up/down buttons.
 */
@Composable
private fun ChannelBanner(state: LiveTvState, onExit: () -> Unit, onZap: (Int) -> Unit) {
    val context = LocalContext.current
    val nowMs = rememberNowMs(1_000L)
    val channel = state.channel
    val (onAir, next) = state.onAir(nowMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.65f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassIconButton(MobileIcons.ChevronLeft, stringResource(R.string.tv_back), onExit)
        if (channel != null) {
            ChannelLogoBadge(name = channel.name, logo = channel.logo, width = 72.dp, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.tv_channel_number, channel.number, channel.name),
                        style = Antresol.typography.title.copy(color = AntresolColors.LabelSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    LiveBadge()
                }
                Text(
                    onAir?.let(Channels::programLine) ?: stringResource(R.string.tv_nothing),
                    style = Antresol.typography.sectionTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onAir != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .widthIn(max = 420.dp),
                    ) {
                        Text(programClock(context, onAir.start), style = Antresol.typography.caption)
                        ProgressBar(fraction = Channels.progress(onAir, nowMs), modifier = Modifier.weight(1f))
                        Text(programClock(context, onAir.end), style = Antresol.typography.caption)
                    }
                }
                if (next != null) {
                    Text(
                        stringResource(R.string.tv_next_at_long, programClock(context, next.start), Channels.programLine(next)),
                        style = Antresol.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        } else {
            Box(Modifier.weight(1f))
        }
        if (state.channels.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundIconButton(MobileIcons.ChevronUp, stringResource(R.string.tv_channel_up), { onZap(1) }, glass = true, size = 40.dp, iconSize = 22.dp, modifier = Modifier.testTag("live-channel-up"))
                RoundIconButton(MobileIcons.ChevronDown, stringResource(R.string.tv_channel_down), { onZap(-1) }, glass = true, size = 40.dp, iconSize = 22.dp, modifier = Modifier.testTag("live-channel-down"))
            }
        }
    }
}

/** The section's channels in a row at the bottom, scrolled to the one on; a tap switches to it. */
@Composable
private fun ChannelStrip(guide: MiniGuide, currentId: Long, onScroll: () -> Unit, onTune: (Channel) -> Unit) {
    val nowMs = rememberNowMs()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = guide.startIndex)
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
            guide.category.ifEmpty { stringResource(R.string.nav_tv) },
            style = Antresol.typography.title,
            modifier = Modifier.padding(start = 16.dp + insets.calculateStartPadding(direction)),
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp + insets.calculateStartPadding(direction),
                end = 16.dp + insets.calculateEndPadding(direction),
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(guide.channels, key = { it.id }) { channel ->
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

@Composable
private fun FailedPanel(message: String?, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val failed = stringResource(R.string.tv_failed)
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .background(AntresolColors.Glass, AntresolShapes.sheet)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(failed, style = Antresol.typography.sectionTitle, textAlign = TextAlign.Center)
        if (message != null && message != failed) {
            Text(message, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            PrimaryPillButton(stringResource(R.string.action_retry), onClick = onRetry, modifier = Modifier.testTag("live-retry"))
            GlassPillButton(stringResource(R.string.player_back), onClick = onBack)
        }
    }
}

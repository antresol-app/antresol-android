package app.antresol.mobile.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import app.antresol.mobile.ui.components.GlassIconButton
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.RoundIconButton
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelLogoBadge
import app.antresol.ui.channels.programClock
import app.antresol.ui.channels.rememberNowMs
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.playback.LivePlayerViewModel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import java.util.Locale
import kotlinx.coroutines.delay

private const val OVERLAY_HIDE_MS = 4_000L

/** A vertical swipe at least this long switches the channel. */
private val ZAP_SWIPE = 72.dp

/**
 * A channel live, edge to edge. A tap shows or hides the overlay (the channel, «Live», the
 * programme on air with its time and progress, what is next, and the channel up/down buttons);
 * a vertical swipe also switches channels: up for the next number, down for the previous one.
 * It is live: no seek bar, no pause.
 */
@Composable
fun LivePlayerScreen(viewModel: LivePlayerViewModel, onExit: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    BackHandler(onBack = onExit)
    val state = viewModel.state
    val stream = state.stream
    var visible by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible, touches, viewModel.isPlaying, state.channelId) {
        if (visible && viewModel.isPlaying) {
            delay(OVERLAY_HIDE_MS)
            visible = false
        }
    }
    val zap: (Int) -> Unit = { step ->
        if (viewModel.zap(step)) {
            visible = true
            touches++
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("live-surface")
            .pointerInput(Unit) { detectTapGestures(onTap = { visible = !visible }) }
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
        VideoSurface(viewModel.player)
        AnimatedVisibility(visible = visible || !viewModel.isPlaying, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            LiveOverlay(state, onExit = onExit, onZap = zap, onTouch = { touches++ })
        }
        when (stream) {
            is LiveStream.Starting -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spinner()
                Text(stringResource(R.string.tv_starting), style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary))
            }
            is LiveStream.Ready -> if (!viewModel.isPlaying || viewModel.isBuffering) {
                Box(Modifier.align(Alignment.Center)) { Spinner() }
            }
            is LiveStream.Failed -> FailedPanel(stream.message, onRetry = viewModel::retry, onBack = onExit, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun LiveOverlay(state: LiveTvState, onExit: () -> Unit, onZap: (Int) -> Unit, onTouch: () -> Unit) {
    val context = LocalContext.current
    val nowMs = rememberNowMs(1_000L)
    val channel = state.channel
    val (guideNow, guideNext) = Channels.onAir(state.programs, nowMs)
    val onAir = guideNow ?: channel?.now
    val next = if (guideNow != null) guideNext else channel?.next
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))) {
        // Top: back and the channel.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassIconButton(MobileIcons.ChevronLeft, stringResource(R.string.tv_back), onExit)
            if (channel != null) {
                ChannelLogoBadge(name = channel.name, logo = channel.logo, width = 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tv_channel_number, channel.number, channel.name),
                        style = Antresol.typography.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(stringResource(R.string.tv_live).uppercase(Locale.getDefault()), style = Antresol.typography.eyebrow, maxLines = 1)
                }
            }
        }
        // Right: channel up and down.
        if (state.channels.size > 1) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(end = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RoundIconButton(MobileIcons.ChevronUp, stringResource(R.string.tv_channel_up), { onTouch(); onZap(1) }, glass = true, size = 52.dp, iconSize = 26.dp, modifier = Modifier.testTag("live-channel-up"))
                RoundIconButton(MobileIcons.ChevronDown, stringResource(R.string.tv_channel_down), { onTouch(); onZap(-1) }, glass = true, size = 52.dp, iconSize = 26.dp, modifier = Modifier.testTag("live-channel-down"))
            }
        }
        // Bottom: the programme on air and what is next.
        if (onAir != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 20.dp, end = 96.dp, top = 24.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(onAir.title, style = Antresol.typography.sectionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onAir.subtitle.isNotBlank()) {
                    Text(onAir.subtitle, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.tv_time, programClock(context, onAir.start), programClock(context, onAir.end)),
                        style = Antresol.typography.caption,
                    )
                    ProgressBar(fraction = Channels.progress(onAir, nowMs), modifier = Modifier.width(220.dp))
                }
                if (next != null) {
                    Text(
                        stringResource(R.string.tv_next_at, programClock(context, next.start), Channels.programLine(next)),
                        style = Antresol.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

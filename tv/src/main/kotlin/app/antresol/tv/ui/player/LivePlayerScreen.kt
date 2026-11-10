package app.antresol.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.antresol.core.channels.Channels
import app.antresol.core.channels.LiveStream
import app.antresol.core.channels.LiveTvState
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelLogoBadge
import app.antresol.ui.channels.programClock
import app.antresol.ui.channels.rememberNowMs
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.playback.LivePlayerViewModel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.delay

private const val OVERLAY_HIDE_MS = 5_000L

/**
 * A channel live, full screen. Up/Down (and Channel+/−, Page Up/Down) switch to the next or
 * previous channel by number, wrapping; any other key shows the overlay: the channel, «Live», the
 * programme on air with its time and progress, and what is next. It hides after 5 s while playing.
 * There is no seeking and no pause: it is live. Back leaves.
 */
@Composable
fun LivePlayerScreen(viewModel: LivePlayerViewModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    val state = viewModel.state
    val stream = state.stream
    var overlayVisible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val failed = stream is LiveStream.Failed

    LaunchedEffect(failed) { if (failed) retryFocus.tryRequestFocus() else rootFocus.tryRequestFocus() }
    LaunchedEffect(overlayVisible, interactions, viewModel.isPlaying, state.channelId) {
        if (overlayVisible && viewModel.isPlaying) {
            delay(OVERLAY_HIDE_MS)
            overlayVisible = false
        }
    }
    val show: () -> Unit = {
        overlayVisible = true
        interactions++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event -> handleLiveKey(event, viewModel, show) }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        VideoSurface(viewModel.player)
        AnimatedVisibility(visible = overlayVisible || !viewModel.isPlaying, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            LiveOverlay(state)
        }
        when (stream) {
            is LiveStream.Starting -> StartingView()
            is LiveStream.Ready -> if (!viewModel.isPlaying || viewModel.isBuffering) Spinner()
            is LiveStream.Failed -> FailedView(stream.message, onRetry = viewModel::retry, retryFocus = retryFocus)
        }
    }
}

/** Channel keys zap (also while the error is shown); the rest only shows the overlay and keeps working. */
private fun handleLiveKey(event: KeyEvent, viewModel: LivePlayerViewModel, show: () -> Unit): Boolean {
    val step = when (event.key) {
        Key.DirectionUp, Key.ChannelUp, Key.PageUp -> 1
        Key.DirectionDown, Key.ChannelDown, Key.PageDown -> -1
        else -> 0
    }
    if (step != 0) {
        if (event.type == KeyEventType.KeyDown) {
            viewModel.zap(step)
            show()
        }
        return true
    }
    if (event.type == KeyEventType.KeyDown && event.key != Key.Back) show()
    return false
}

@Composable
private fun LiveOverlay(state: LiveTvState) {
    val context = LocalContext.current
    val nowMs = rememberNowMs(1_000L)
    val channel = state.channel
    val (guideNow, guideNext) = Channels.onAir(state.programs, nowMs)
    val onAir = guideNow ?: channel?.now
    val next = if (guideNow != null) guideNext else channel?.next
    Box(Modifier.fillMaxSize()) {
        // Top: the channel.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                .padding(start = ScreenPaddingH, end = ScreenPaddingH, top = 32.dp, bottom = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (channel != null) {
                ChannelLogoBadge(name = channel.name, logo = channel.logo, width = 72.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.tv_channel_number, channel.number, channel.name),
                        style = Antresol.typography.sectionTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(stringResource(R.string.tv_live).uppercase(), style = Antresol.typography.eyebrow)
                }
            }
            if (state.channels.size > 1) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(AntresolIcons.ChevronUp, contentDescription = stringResource(R.string.tv_channel_up), tint = AntresolColors.LabelSecondary, modifier = Modifier.size(22.dp))
                    Icon(AntresolIcons.ChevronDown, contentDescription = stringResource(R.string.tv_channel_down), tint = AntresolColors.LabelSecondary, modifier = Modifier.size(22.dp))
                }
            }
        }
        // Bottom: the programme on air and what is next.
        if (onAir != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(start = ScreenPaddingH, end = ScreenPaddingH, top = 64.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(onAir.title, style = Antresol.typography.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (onAir.subtitle.isNotBlank()) {
                    Text(onAir.subtitle, style = Antresol.typography.body, color = AntresolColors.LabelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(
                        stringResource(R.string.tv_time, programClock(context, onAir.start), programClock(context, onAir.end)),
                        style = Antresol.typography.caption,
                    )
                    ProgressBar(fraction = Channels.progress(onAir, nowMs), modifier = Modifier.width(280.dp))
                }
                if (next != null) {
                    Text(
                        stringResource(R.string.tv_next_at, programClock(context, next.start), Channels.programLine(next)),
                        style = Antresol.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StartingView() {
    Box(Modifier.fillMaxSize()) {
        Spinner()
        Text(
            stringResource(R.string.tv_starting),
            style = Antresol.typography.body,
            color = AntresolColors.LabelSecondary,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 96.dp),
        )
    }
}

@Composable
private fun FailedView(message: String?, onRetry: () -> Unit, retryFocus: FocusRequester) {
    val failed = stringResource(R.string.tv_failed)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .background(AntresolColors.Glass, AntresolShapes.sheet)
                .padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(failed, style = Antresol.typography.sectionTitle, textAlign = TextAlign.Center)
            if (message != null && message != failed) {
                Text(message, style = Antresol.typography.body, color = AntresolColors.LabelSecondary, textAlign = TextAlign.Center)
            }
            PillButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
                style = PillStyle.Primary,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .focusRequester(retryFocus),
            )
        }
    }
}

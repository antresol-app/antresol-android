package app.antresol.mobile.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.antresol.mobile.R
import app.antresol.mobile.ui.components.GlassIconButton
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.RoundIconButton
import app.antresol.mobile.ui.components.SkipSecondsButton
import app.antresol.mobile.ui.components.TimeSlider
import app.antresol.ui.R as UiR
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.playback.PlaybackUnavailableException
import app.antresol.ui.playback.VideoPlayerViewModel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.trailers.EmbedPhase
import app.antresol.ui.trailers.TrailerEmbedState
import app.antresol.ui.trailers.TrailerEmbedView
import app.antresol.ui.trailers.TrailerPhase
import app.antresol.ui.trailers.TrailerPlayerViewModel
import java.util.Locale
import kotlinx.coroutines.delay

private const val CONTROLS_HIDE_MS = 4_000L

/** Test tags of the trailer screens. */
internal object TrailerTags {
    const val LOADING = "trailer-loading"
    const val PLAYING = "trailer-playing"
    const val FAILED = "trailer-failed"
}

/**
 * A trailer the server fetched, in the app's own player: the name and a spinner while the server
 * fetches it, then the film player's controls (a tap shows them; back, skip 10 s, play/pause, the
 * scrubber; no track menus). Back leaves. No progress is reported.
 */
@Composable
fun TrailerPlayerScreen(viewModel: TrailerPlayerViewModel, onExit: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    val phase = viewModel.phase
    LaunchedEffect(phase) { if (phase == TrailerPhase.Finished) onExit() }
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(viewModel.player)
        when (phase) {
            TrailerPhase.Loading -> LoadingState(viewModel.name, onExit)
            TrailerPhase.Playing -> PlayingLayer(viewModel, onExit)
            is TrailerPhase.Failed -> FailedState(viewModel.name, phase.error, onRetry = viewModel::retry, onExit = onExit)
            TrailerPhase.Finished -> Unit
        }
    }
}

/**
 * A trailer the server does not fetch, in the official embedded player (a WebView), its own
 * controls on the picture; our back button over it. Back leaves and destroys the WebView.
 */
@Composable
fun TrailerEmbedScreen(trailerKey: String, name: String, onExit: () -> Unit) {
    val state = remember(trailerKey) { TrailerEmbedState(trailerKey) }
    val phase = state.phase
    LaunchedEffect(phase) { if (phase == EmbedPhase.Finished) onExit() }
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        TrailerEmbedView(state, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
        when (phase) {
            EmbedPhase.Loading -> LoadingState(name, onExit)
            EmbedPhase.Playing -> Box(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .testTag(TrailerTags.PLAYING),
            ) {
                GlassIconButton(MobileIcons.ChevronLeft, stringResource(UiR.string.player_back), onExit)
            }
            is EmbedPhase.Failed -> FailedState(name, PlaybackUnavailableException(), onRetry = state::reload, onExit = onExit)
            EmbedPhase.Finished -> Unit
        }
    }
}

@Composable
private fun PlayingLayer(viewModel: TrailerPlayerViewModel, onExit: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(visible, viewModel.isPlaying, touches, scrubbing) {
        if (visible && viewModel.isPlaying && !scrubbing) {
            delay(CONTROLS_HIDE_MS)
            visible = false
        }
    }
    val touched: () -> Unit = { touches++ }
    Box(
        Modifier
            .fillMaxSize()
            .testTag(TrailerTags.PLAYING)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { visible = !visible },
                    onDoubleTap = { offset ->
                        val third = size.width / 3f
                        when {
                            offset.x < third -> viewModel.seekBy(-VideoPlayerViewModel.SEEK_STEP_MS)
                            offset.x > 2 * third -> viewModel.seekBy(VideoPlayerViewModel.SEEK_STEP_MS)
                            else -> viewModel.togglePlayPause()
                        }
                    },
                )
            },
    ) {
        if (viewModel.isBuffering && viewModel.playWhenReady) {
            Box(Modifier.align(Alignment.Center)) { Spinner() }
        }
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                TopBar(viewModel.name, onExit, Modifier.align(Alignment.TopCenter))
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkipSecondsButton(10, forward = false, stringResource(R.string.player_skip_back, 10), { touched(); viewModel.seekBy(-VideoPlayerViewModel.SEEK_STEP_MS) }, glass = true, size = 56.dp)
                    val playing = viewModel.playWhenReady
                    RoundIconButton(
                        icon = if (playing) AntresolIcons.Pause else AntresolIcons.Play,
                        contentDescription = stringResource(if (playing) UiR.string.player_pause else UiR.string.action_play),
                        onClick = { touched(); viewModel.togglePlayPause() },
                        size = 76.dp,
                        iconSize = 34.dp,
                        filled = true,
                        modifier = Modifier.testTag("player-play-pause"),
                    )
                    SkipSecondsButton(10, forward = true, stringResource(R.string.player_skip_forward, 10), { touched(); viewModel.seekBy(VideoPlayerViewModel.SEEK_STEP_MS) }, glass = true, size = 56.dp)
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    TimeSlider(
                        positionMs = viewModel.positionMs,
                        durationMs = viewModel.durationMs,
                        onSeek = { touched(); viewModel.seekTo(it) },
                        onScrubbing = { active ->
                            scrubbing = active
                            if (active) visible = true else touched()
                        },
                    )
                }
            }
        }
    }
}

/** Back and the trailer's name, where the film player shows its title. */
@Composable
private fun TopBar(name: String, onExit: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIconButton(MobileIcons.ChevronLeft, stringResource(UiR.string.player_back), onExit)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(UiR.string.action_trailer).uppercase(Locale.getDefault()),
                style = Antresol.typography.eyebrow,
                maxLines = 1,
            )
            Text(name, style = Antresol.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LoadingState(name: String, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag(TrailerTags.LOADING)) {
        TopBar(name, onExit)
        Box(Modifier.align(Alignment.Center)) { Spinner() }
    }
}

@Composable
private fun FailedState(name: String, error: Throwable, onRetry: () -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag(TrailerTags.FAILED)) {
        TopBar(name, onExit)
        Box(Modifier.align(Alignment.Center).padding(24.dp)) { FailedPanel(error, onRetry = onRetry, onBack = onExit) }
    }
}

package app.antresol.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Text
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.R
import app.antresol.ui.playback.PlaybackUnavailableException
import app.antresol.ui.playback.VideoPlayerViewModel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.trailers.EmbedPhase
import app.antresol.ui.trailers.TrailerEmbedState
import app.antresol.ui.trailers.TrailerEmbedView
import app.antresol.ui.trailers.TrailerPhase
import app.antresol.ui.trailers.TrailerPlayerViewModel
import app.antresol.ui.trailers.TrailerTags

/**
 * A trailer the server fetched, full screen in the app's own player: the trailer's name and a
 * spinner while the server fetches it, then the film player's controls (seek bar, play/pause; no
 * track menus). Back leaves at once, from every state. No progress is reported.
 */
@Composable
fun TrailerPlayerScreen(viewModel: TrailerPlayerViewModel, onExit: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    val phase = viewModel.phase
    LaunchedEffect(phase) { if (phase == TrailerPhase.Finished) onExit() }
    TrailerFrame(onExit) {
        VideoSurface(viewModel.player)
        when (phase) {
            TrailerPhase.Loading -> LoadingState(viewModel.name)
            TrailerPhase.Playing -> Box(Modifier.fillMaxSize().testTag(TrailerTags.PLAYING)) {
                PlayingOverlayContent(
                    transport = TransportState(
                        positionMs = { viewModel.positionMs },
                        bufferedMs = { viewModel.bufferedMs },
                        durationMs = viewModel.durationMs,
                        playing = viewModel.playWhenReady,
                        audioChoice = false,
                        subtitleChoice = false,
                        nextEpisode = false,
                        qualityChoice = false,
                    ),
                    isPlaying = viewModel.isPlaying,
                    busy = viewModel.isBuffering,
                    title = { TrailerTitle(viewModel.name) },
                    actions = trailerActions(viewModel),
                    menuContent = { _, _ -> },
                    onBack = onExit,
                )
            }
            is TrailerPhase.Failed -> FailedState(viewModel.name, phase.error, onRetry = viewModel::retry)
            TrailerPhase.Finished -> Unit
        }
    }
}

/**
 * A trailer the server does not fetch, in the official embedded player (a WebView) full screen:
 * the name and a spinner until it plays, OK pauses and resumes it, Back leaves and destroys it.
 */
@Composable
fun TrailerEmbedScreen(trailerKey: String, name: String, onExit: () -> Unit) {
    val state = remember(trailerKey) { TrailerEmbedState(trailerKey) }
    val phase = state.phase
    LaunchedEffect(phase) { if (phase == EmbedPhase.Finished) onExit() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(phase) { if (phase != EmbedPhase.Loading && phase !is EmbedPhase.Failed) focus.tryRequestFocus() }
    TrailerFrame(onExit) {
        TrailerEmbedView(state, Modifier.fillMaxSize())
        when (phase) {
            EmbedPhase.Loading -> LoadingState(name)
            EmbedPhase.Playing -> Box(
                Modifier
                    .fillMaxSize()
                    .testTag(TrailerTags.PLAYING)
                    .focusRequester(focus)
                    .onKeyEvent { event ->
                        val toggle = event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause)
                        if (toggle && event.type == KeyEventType.KeyDown) state.toggle()
                        toggle
                    }
                    .focusable(),
            )
            is EmbedPhase.Failed -> FailedState(name, PlaybackUnavailableException(), onRetry = state::reload)
            EmbedPhase.Finished -> Unit
        }
    }
}

/** Black, full screen; Back leaves (taken before a focused button would use it as a focus exit). */
@Composable
private fun TrailerFrame(onExit: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onExit)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Back) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) onExit()
                true
            },
    ) {
        content()
    }
}

@Composable
private fun LoadingState(name: String) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag(TrailerTags.LOADING)) {
        TitleBar(name)
        Spinner()
    }
}

@Composable
private fun FailedState(name: String, error: Throwable, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag(TrailerTags.FAILED)) {
        TitleBar(name)
        ErrorView(error, onRetry = onRetry)
    }
}

/** The name where the player's controls show it, so nothing moves when the trailer starts. */
@Composable
private fun TitleBar(name: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
            .padding(start = ScreenPaddingH, end = ScreenPaddingH, top = 32.dp, bottom = 48.dp),
    ) {
        TrailerTitle(name)
    }
}

@Composable
private fun TrailerTitle(name: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.widthIn(max = 900.dp)) {
        Text(stringResource(R.string.action_trailer).uppercase(), style = Antresol.typography.eyebrow, maxLines = 1)
        Text(name, style = Antresol.typography.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The film player's keys, minus scrubbing: Left/Right seek 10 s, OK and media keys pause. */
private fun trailerActions(viewModel: TrailerPlayerViewModel): TransportActions {
    val seekKey: (KeyEvent) -> Boolean = { event ->
        val step = when (event.key) {
            Key.DirectionRight -> VideoPlayerViewModel.SEEK_STEP_MS
            Key.DirectionLeft -> -VideoPlayerViewModel.SEEK_STEP_MS
            else -> null
        }
        if (step != null && event.type == KeyEventType.KeyDown) viewModel.seekBy(step)
        step != null
    }
    return TransportActions(
        onSeek = viewModel::seekBy,
        onToggle = viewModel::togglePlayPause,
        onNext = {},
        onScrubKey = seekKey,
        onMediaKey = { event ->
            val action: (() -> Unit)? = when (event.key) {
                Key.MediaPlayPause -> viewModel::togglePlayPause
                Key.MediaPlay -> { { viewModel.player.play() } }
                Key.MediaPause -> { { viewModel.player.pause() } }
                Key.MediaFastForward -> { { viewModel.seekBy(VideoPlayerViewModel.SEEK_STEP_MS) } }
                Key.MediaRewind -> { { viewModel.seekBy(-VideoPlayerViewModel.SEEK_STEP_MS) } }
                else -> null
            }
            if (action != null && event.type == KeyEventType.KeyDown) action()
            action != null
        },
        onHiddenKey = { event, show ->
            val handled = event.key in setOf(
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
            )
            if (handled && event.type == KeyEventType.KeyDown) {
                if (!seekKey(event) && event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) viewModel.togglePlayPause()
                show()
            }
            handled
        },
    )
}

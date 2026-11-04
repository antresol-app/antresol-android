package app.antresol.tv.ui.player

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import app.antresol.core.model.ItemDetail
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.GlassNotice
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.tv.ui.home.HeroBackground
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.format.Durations
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.delay

private const val CONTROLS_HIDE_MS = 5_000L
private const val NOTICE_MS = 3_500L

/**
 * The full-screen video player. With the controls hidden, OK toggles pause, Left/Right seek (10 s a
 * press, faster while held) and Up/Down show the controls; 0–9 jump to 0–90 %. Back hides the
 * controls, closes a menu or leaves the player.
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onExit: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    val phase = viewModel.phase
    LaunchedEffect(phase) { if (phase == PlayerPhase.Finished) onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VideoSurface(viewModel.player)
        when (phase) {
            PlayerPhase.Loading -> Backdrop(viewModel.item) { Spinner() }
            is PlayerPhase.AskResume -> Backdrop(viewModel.item) {
                ResumePrompt(viewModel.item, phase.positionMs, onChoose = viewModel::chooseResume)
            }
            PlayerPhase.Playing -> PlayingOverlay(viewModel)
            is PlayerPhase.UpNext -> UpNextOverlay(phase, onPlayNow = viewModel::playNext, onCancel = viewModel::cancelUpNext)
            is PlayerPhase.Failed -> Backdrop(viewModel.item) { ErrorView(phase.error, onRetry = viewModel::retry) }
            PlayerPhase.Finished -> Unit
        }
        Notice(viewModel)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: Player) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(AndroidColor.BLACK)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                subtitleView?.apply {
                    setApplyEmbeddedStyles(false)
                    setFractionalTextSize(0.052f)
                    setStyle(
                        CaptionStyleCompat(
                            AndroidColor.WHITE, AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, AndroidColor.BLACK, null,
                        ),
                    )
                }
                this.player = player
            }
        },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

/** The artwork-tinted backdrop of the item behind loading, prompts and errors. */
@Composable
private fun Backdrop(item: ItemDetail?, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        HeroBackground(item?.toCard())
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
        )
        content()
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AntresolColors.Label, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
    }
}

@Composable
private fun ResumePrompt(item: ItemDetail?, positionMs: Long, onChoose: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.tryRequestFocus() }
    Box(Modifier.fillMaxSize().padding(horizontal = ScreenPaddingH, vertical = 56.dp), contentAlignment = Alignment.BottomStart) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 640.dp)) {
            TitleBlock(item)
            Text(stringResource(R.string.player_resume_title), style = Antresol.typography.bodyEmphasis, color = AntresolColors.LabelSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                PillButton(
                    text = stringResource(R.string.player_resume_from, Durations.clock(positionMs)),
                    onClick = { onChoose(true) },
                    icon = AntresolIcons.Play,
                    style = PillStyle.Primary,
                    modifier = Modifier.focusRequester(focus),
                )
                PillButton(
                    text = stringResource(R.string.action_play_from_start),
                    onClick = { onChoose(false) },
                    icon = AntresolIcons.Restart,
                )
            }
        }
    }
}

@Composable
private fun TitleBlock(item: ItemDetail?) {
    if (item == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.subtitle.isNotBlank()) {
            Text(item.subtitle.uppercase(), style = Antresol.typography.eyebrow, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(item.title, style = Antresol.typography.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Video with the transport controls, the buffering spinner and the track menus. */
@Composable
private fun PlayingOverlay(viewModel: PlayerViewModel) {
    var controlsVisible by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf<PlayerMenu?>(null) }
    var interactions by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }

    LaunchedEffect(controlsVisible, menu) {
        when {
            menu != null -> Unit
            controlsVisible -> seekFocus.tryRequestFocus()
            else -> rootFocus.tryRequestFocus()
        }
    }
    LaunchedEffect(controlsVisible, interactions, viewModel.isPlaying, menu) {
        if (controlsVisible && viewModel.isPlaying && menu == null) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }
    BackHandler(enabled = menu != null) { menu = null }
    BackHandler(enabled = menu == null && controlsVisible) { controlsVisible = false }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                interactions++
                handleMediaKey(event, viewModel)
            }
            .focusRequester(rootFocus)
            .onKeyEvent { event ->
                if (controlsVisible) return@onKeyEvent false
                handleHiddenKey(event, viewModel) { controlsVisible = true }
            }
            .focusable(),
    ) {
        if (viewModel.isBuffering || viewModel.isSwitching) Spinner()
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            TransportControls(
                viewModel = viewModel,
                seekFocus = seekFocus,
                onOpenMenu = { menu = it },
            )
        }
        menu?.let { open ->
            TrackMenu(
                menu = open,
                viewModel = viewModel,
                onDismiss = { menu = null },
            )
        }
    }
}

private val DigitKeys = listOf(
    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
)

/** Left/Right on the seek bar or with the controls hidden: scrub while held, seek on release. */
private fun handleScrubKey(event: KeyEvent, viewModel: PlayerViewModel): Boolean {
    val forward = when (event.key) {
        Key.DirectionRight -> true
        Key.DirectionLeft -> false
        else -> return false
    }
    when (event.type) {
        KeyEventType.KeyDown -> viewModel.scrub(forward, event.nativeKeyEvent.repeatCount)
        KeyEventType.KeyUp -> viewModel.commitScrub()
    }
    return true
}

/** Remote media keys and number keys work whether or not the controls are showing. */
private fun handleMediaKey(event: KeyEvent, viewModel: PlayerViewModel): Boolean {
    val digit = DigitKeys.indexOf(event.key)
    if (digit >= 0) {
        if (event.type == KeyEventType.KeyDown) viewModel.jumpToTenth(digit)
        return true
    }
    val action: (() -> Unit) = when (event.key) {
        Key.MediaPlayPause -> viewModel::togglePlayPause
        Key.MediaPlay -> { { viewModel.player.play() } }
        Key.MediaPause -> { { viewModel.player.pause() } }
        Key.MediaFastForward -> { { viewModel.seekBy(PlayerViewModel.SEEK_STEP_MS) } }
        Key.MediaRewind -> { { viewModel.seekBy(-PlayerViewModel.SEEK_STEP_MS) } }
        Key.MediaNext -> viewModel::playNext
        else -> return false
    }
    if (event.type == KeyEventType.KeyDown) action()
    return true
}

/** D-pad with the controls hidden: OK pauses, Left/Right seek, every key shows the controls. */
private fun handleHiddenKey(event: KeyEvent, viewModel: PlayerViewModel, show: () -> Unit): Boolean {
    val handled = event.key in setOf(
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
    )
    if (!handled) return false
    if (handleScrubKey(event, viewModel)) {
        if (event.type == KeyEventType.KeyDown) show()
        return true
    }
    if (event.type != KeyEventType.KeyDown) return true
    if (event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) viewModel.togglePlayPause()
    show()
    return true
}

@Composable
private fun TransportControls(
    viewModel: PlayerViewModel,
    seekFocus: FocusRequester,
    onOpenMenu: (PlayerMenu) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Top: what is playing.
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                .padding(start = ScreenPaddingH, end = ScreenPaddingH, top = 32.dp, bottom = 48.dp),
        ) {
            TitleBlock(viewModel.item)
        }
        // Bottom: progress and buttons.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(start = ScreenPaddingH, end = ScreenPaddingH, top = 64.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SeekBar(
                positionMs = viewModel.positionMs,
                bufferedMs = viewModel.bufferedMs,
                durationMs = viewModel.durationMs,
                onSeek = viewModel::seekBy,
                onToggle = viewModel::togglePlayPause,
                modifier = Modifier
                    .focusRequester(seekFocus)
                    .onPreviewKeyEvent { handleScrubKey(it, viewModel) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val playing = viewModel.playWhenReady
                PillButton(
                    text = stringResource(if (playing) R.string.player_pause else R.string.action_play),
                    onClick = viewModel::togglePlayPause,
                    icon = if (playing) AntresolIcons.Pause else AntresolIcons.Play,
                    style = PillStyle.Primary,
                )
                if (viewModel.audioTracks.size > 1) {
                    PillButton(stringResource(R.string.detail_audio), onClick = { onOpenMenu(PlayerMenu.Audio) }, icon = AntresolIcons.Audio)
                }
                if (viewModel.subtitleTracks.isNotEmpty()) {
                    PillButton(stringResource(R.string.detail_subtitles), onClick = { onOpenMenu(PlayerMenu.Subtitles) }, icon = AntresolIcons.Subtitles)
                }
                PillButton(stringResource(R.string.detail_quality), onClick = { onOpenMenu(PlayerMenu.Quality) }, icon = AntresolIcons.Sliders)
                Spacer(Modifier.weight(1f))
                if (viewModel.nextEpisode != null) {
                    PillButton(stringResource(R.string.player_next_episode), onClick = viewModel::playNext, icon = AntresolIcons.SkipNext)
                }
            }
        }
    }
}

@Composable
private fun UpNextOverlay(phase: PlayerPhase.UpNext, onPlayNow: () -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.tryRequestFocus() }
    BackHandler(onBack = onCancel)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = ScreenPaddingH, vertical = 48.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Text(stringResource(R.string.player_up_next).uppercase(), style = Antresol.typography.eyebrow)
            Text(phase.episode.title, style = Antresol.typography.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (phase.episode.subtitle.isNotBlank()) {
                Text(phase.episode.subtitle, style = Antresol.typography.body, color = AntresolColors.LabelSecondary, maxLines = 1)
            }
            Text(
                stringResource(R.string.player_next_in, phase.secondsLeft),
                style = Antresol.typography.caption,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
                PillButton(
                    text = stringResource(R.string.player_play_now),
                    onClick = onPlayNow,
                    icon = AntresolIcons.Play,
                    style = PillStyle.Primary,
                    modifier = Modifier.focusRequester(focus),
                )
            }
        }
    }
}

/** A short message pill at the top (for example, subtitles that failed to load). */
@Composable
private fun Notice(viewModel: PlayerViewModel) {
    val message = viewModel.notice ?: return
    LaunchedEffect(message) {
        delay(NOTICE_MS)
        viewModel.notice = null
    }
    Box(Modifier.fillMaxWidth().padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
        GlassNotice(stringResource(message))
    }
}

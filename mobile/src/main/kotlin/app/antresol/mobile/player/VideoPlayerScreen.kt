package app.antresol.mobile.player

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.PlayMode
import app.antresol.core.model.Quality
import app.antresol.mobile.R
import app.antresol.mobile.ui.components.GlassIconButton
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.RoundIconButton
import app.antresol.mobile.ui.components.SkipSecondsButton
import app.antresol.mobile.ui.components.TimeSlider
import app.antresol.mobile.ui.userMessage
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.format.Durations
import app.antresol.ui.format.TrackLabels
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.delay
import java.util.Locale
import app.antresol.ui.R as UiR

private const val CONTROLS_HIDE_MS = 4_000L
private const val NOTICE_MS = 3_500L
private const val SEEK_FLASH_MS = 700L

private enum class PlayerMenu { Audio, Subtitles, Quality }

/**
 * The landscape video player: the picture edge to edge with controls on top. A tap shows or
 * hides the controls, a double tap on the left or right third skips 10 s, in the middle it
 * pauses. In picture-in-picture only the picture is drawn.
 */
@Composable
fun VideoPlayerScreen(
    viewModel: VideoPlayerViewModel,
    inPictureInPicture: Boolean,
    canPictureInPicture: Boolean,
    onPictureInPicture: () -> Unit,
    onExit: () -> Unit,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    val phase = viewModel.phase
    LaunchedEffect(phase) { if (phase == PlayerPhase.Finished) onExit() }
    BackHandler(onBack = onExit)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(viewModel.player)
        if (inPictureInPicture) return@Box
        when (phase) {
            PlayerPhase.Loading -> Overlay(viewModel.item, onExit) { Spinner() }
            is PlayerPhase.AskResume -> Overlay(viewModel.item, onExit) {
                ResumePrompt(phase.positionMs, onChoose = viewModel::chooseResume)
            }
            PlayerPhase.Playing -> PlayingLayer(viewModel, canPictureInPicture, onPictureInPicture, onExit)
            is PlayerPhase.UpNext -> UpNextPanel(phase, onPlayNow = viewModel::playNext, onClose = onExit)
            is PlayerPhase.Failed -> Overlay(viewModel.item, onExit) {
                FailedPanel(phase.error, onRetry = viewModel::retry, onBack = onExit)
            }
            PlayerPhase.Finished -> Unit
        }
        Notice(viewModel)
    }
}

@Composable
private fun VideoSurface(player: Player) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(AndroidColor.BLACK)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                subtitleView?.apply {
                    setApplyEmbeddedStyles(false)
                    setFractionalTextSize(0.055f)
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
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun PlayingLayer(
    viewModel: VideoPlayerViewModel,
    canPictureInPicture: Boolean,
    onPictureInPicture: () -> Unit,
    onExit: () -> Unit,
) {
    var visible by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf<PlayerMenu?>(null) }
    // Bumped on every interaction so the hide timer restarts.
    var touches by remember { mutableIntStateOf(0) }
    var seekFlash by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(visible, viewModel.isPlaying, menu, touches) {
        if (visible && viewModel.isPlaying && menu == null) {
            delay(CONTROLS_HIDE_MS)
            visible = false
        }
    }
    LaunchedEffect(seekFlash) {
        if (seekFlash != null) {
            delay(SEEK_FLASH_MS)
            seekFlash = null
        }
    }
    val touched: () -> Unit = { touches++ }

    Box(
        Modifier
            .fillMaxSize()
            .testTag("player-surface")
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { visible = !visible },
                    onDoubleTap = { offset ->
                        val third = size.width / 3f
                        when {
                            offset.x < third -> {
                                viewModel.seekBy(-VideoPlayerViewModel.SEEK_STEP_MS)
                                seekFlash = -1
                            }
                            offset.x > 2 * third -> {
                                viewModel.seekBy(VideoPlayerViewModel.SEEK_STEP_MS)
                                seekFlash = 1
                            }
                            else -> viewModel.togglePlayPause()
                        }
                    },
                )
            },
    ) {
        seekFlash?.let { direction ->
            Text(
                if (direction < 0) "−10" else "+10",
                style = Antresol.typography.pageTitle,
                modifier = Modifier
                    .align(if (direction < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 64.dp)
                    .background(AntresolColors.Glass.copy(alpha = 0.6f), AntresolShapes.pill)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        if ((viewModel.isBuffering && viewModel.playWhenReady) || viewModel.isSwitching) {
            Box(Modifier.align(Alignment.Center)) { Spinner() }
        }
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Controls(viewModel, canPictureInPicture, onPictureInPicture, onExit, onMenu = { menu = it }, onTouch = touched)
        }
    }
    menu?.let { open -> MenuSheet(open, viewModel, onDismiss = { menu = null }) }
}

@Composable
private fun Controls(
    viewModel: VideoPlayerViewModel,
    canPictureInPicture: Boolean,
    onPictureInPicture: () -> Unit,
    onExit: () -> Unit,
    onMenu: (PlayerMenu) -> Unit,
    onTouch: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
        // Top: back, title, track menus.
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
            GlassIconButton(MobileIcons.ChevronLeft, stringResource(UiR.string.player_back), onExit)
            TitleBlock(viewModel.item, Modifier.weight(1f))
            if (viewModel.audioTracks.size > 1) {
                RoundIconButton(AntresolIcons.Audio, stringResource(UiR.string.detail_audio), { onTouch(); onMenu(PlayerMenu.Audio) }, glass = true, size = 44.dp, iconSize = 22.dp)
            }
            if (viewModel.subtitleTracks.isNotEmpty()) {
                RoundIconButton(AntresolIcons.Subtitles, stringResource(UiR.string.detail_subtitles), { onTouch(); onMenu(PlayerMenu.Subtitles) }, glass = true, size = 44.dp, iconSize = 22.dp)
            }
            RoundIconButton(AntresolIcons.Sliders, stringResource(UiR.string.detail_quality), { onTouch(); onMenu(PlayerMenu.Quality) }, glass = true, size = 44.dp, iconSize = 22.dp)
            if (canPictureInPicture) {
                RoundIconButton(MobileIcons.Pip, stringResource(R.string.player_pip), onPictureInPicture, glass = true, size = 44.dp, iconSize = 22.dp)
            }
        }
        // Centre: skip back, play/pause, skip forward.
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkipSecondsButton(10, forward = false, stringResource(R.string.player_skip_back, 10), { onTouch(); viewModel.seekBy(-VideoPlayerViewModel.SEEK_STEP_MS) }, glass = true, size = 56.dp)
            val playing = viewModel.playWhenReady
            RoundIconButton(
                icon = if (playing) AntresolIcons.Pause else AntresolIcons.Play,
                contentDescription = stringResource(if (playing) UiR.string.player_pause else UiR.string.action_play),
                onClick = { onTouch(); viewModel.togglePlayPause() },
                size = 76.dp,
                iconSize = 34.dp,
                filled = true,
                modifier = Modifier.testTag("player-play-pause"),
            )
            SkipSecondsButton(10, forward = true, stringResource(R.string.player_skip_forward, 10), { onTouch(); viewModel.seekBy(VideoPlayerViewModel.SEEK_STEP_MS) }, glass = true, size = 56.dp)
        }
        // Bottom: scrubber and Next Episode.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            if (viewModel.nextEpisode != null) {
                GlassPillButton(
                    text = stringResource(UiR.string.player_next_episode),
                    icon = MobileIcons.SkipNext,
                    onClick = viewModel::playNext,
                    modifier = Modifier.align(Alignment.End).testTag("player-next-episode"),
                )
            }
            TimeSlider(
                positionMs = viewModel.positionMs,
                durationMs = viewModel.durationMs,
                onSeek = { onTouch(); viewModel.seekTo(it) },
            )
        }
    }
}

@Composable
private fun TitleBlock(item: ItemDetail?, modifier: Modifier = Modifier) {
    item ?: return Spacer(modifier)
    Column(modifier) {
        val eyebrow = if (item.kind == Kind.EPISODE) item.subtitle else ""
        if (eyebrow.isNotBlank()) {
            Text(eyebrow.uppercase(Locale.getDefault()), style = Antresol.typography.eyebrow, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(item.title, style = Antresol.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Loading, resume and error states: the title over black with a back button. */
@Composable
private fun Overlay(item: ItemDetail?, onExit: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))) {
        Row(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassIconButton(MobileIcons.ChevronLeft, stringResource(UiR.string.player_back), onExit)
            TitleBlock(item)
        }
        Box(Modifier.align(Alignment.Center).padding(24.dp)) { content() }
    }
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(color = AntresolColors.Label, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
}

@Composable
private fun ResumePrompt(positionMs: Long, onChoose: (Boolean) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.widthIn(max = 360.dp),
    ) {
        Text(stringResource(UiR.string.player_resume_title), style = Antresol.typography.sectionTitle)
        PrimaryPillButton(
            text = stringResource(UiR.string.player_resume_from, Durations.clock(positionMs)),
            icon = AntresolIcons.Play,
            onClick = { onChoose(true) },
            modifier = Modifier.fillMaxWidth().testTag("player-resume"),
        )
        GlassPillButton(
            text = stringResource(UiR.string.action_play_from_start),
            icon = AntresolIcons.Restart,
            onClick = { onChoose(false) },
            modifier = Modifier.fillMaxWidth().testTag("player-start-over"),
        )
    }
}

@Composable
private fun FailedPanel(error: Throwable, onRetry: () -> Unit, onBack: () -> Unit) {
    val message = if (error is PlaybackUnavailableException) {
        stringResource(UiR.string.error_playback)
    } else {
        error.userMessage(LocalContext.current.resources)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 420.dp)) {
        Text(message, style = Antresol.typography.body, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryPillButton(stringResource(UiR.string.action_retry), onClick = onRetry)
            GlassPillButton(stringResource(UiR.string.player_back), onClick = onBack)
        }
    }
}

@Composable
private fun UpNextPanel(phase: PlayerPhase.UpNext, onPlayNow: () -> Unit, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
            modifier = Modifier.widthIn(max = 380.dp),
        ) {
            Text(stringResource(UiR.string.player_up_next).uppercase(Locale.getDefault()), style = Antresol.typography.eyebrow)
            Text(phase.episode.title, style = Antresol.typography.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            if (phase.episode.subtitle.isNotBlank()) {
                Text(phase.episode.subtitle, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), maxLines = 1)
            }
            phase.secondsLeft?.let { Text(stringResource(UiR.string.player_next_in, it), style = Antresol.typography.caption) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                GlassPillButton(stringResource(UiR.string.player_back), onClick = onClose)
                PrimaryPillButton(stringResource(UiR.string.player_play_now), onClick = onPlayNow, icon = AntresolIcons.Play, modifier = Modifier.testTag("player-play-now"))
            }
        }
    }
}

private data class MenuOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(menu: PlayerMenu, viewModel: VideoPlayerViewModel, onDismiss: () -> Unit) {
    val resources = LocalContext.current.resources
    val title = stringResource(
        when (menu) {
            PlayerMenu.Audio -> UiR.string.detail_audio
            PlayerMenu.Subtitles -> UiR.string.detail_subtitles
            PlayerMenu.Quality -> UiR.string.detail_quality
        },
    )
    val options = when (menu) {
        PlayerMenu.Audio -> viewModel.audioTracks.map { track ->
            MenuOption(TrackLabels.audio(resources, track), track.index == viewModel.selectedAudioIndex) { viewModel.selectAudio(track) }
        }
        PlayerMenu.Subtitles -> listOf(
            MenuOption(stringResource(UiR.string.detail_subtitles_off), viewModel.selectedSubtitle == null) { viewModel.selectSubtitle(null) },
        ) + viewModel.subtitleTracks.map { track ->
            MenuOption(TrackLabels.subtitle(resources, track), track.index == viewModel.selectedSubtitle?.index) { viewModel.selectSubtitle(track) }
        }
        PlayerMenu.Quality -> Quality.ALL.map { value ->
            MenuOption(qualityLabel(value), value == viewModel.quality) { viewModel.selectQuality(value) }
        }
    }
    val note = if (menu == PlayerMenu.Quality) streamNote(viewModel) else null
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(title, style = Antresol.typography.sectionTitle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (note != null) {
                Text(note, style = Antresol.typography.caption, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
            }
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            option.onSelect()
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
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
fun qualityLabel(value: String): String = when (value) {
    Quality.AUTO -> stringResource(UiR.string.quality_auto)
    Quality.ORIGINAL -> stringResource(UiR.string.quality_original)
    else -> "${value}p"
}

/** "Direct Stream · container mkv isn't supported…": how the server delivers the stream and why. */
@Composable
private fun streamNote(viewModel: VideoPlayerViewModel): String? {
    val play = viewModel.source?.play ?: return null
    val mode = stringResource(
        when (play.mode) {
            PlayMode.DIRECT -> UiR.string.player_mode_direct
            PlayMode.REMUX -> UiR.string.player_mode_remux
            else -> UiR.string.player_mode_transcode
        },
    )
    return listOf(mode, play.reason).filter { it.isNotBlank() }.joinToString(" · ")
}

/** A short message pill at the top (for example, subtitles that failed to load). */
@Composable
private fun Notice(viewModel: VideoPlayerViewModel) {
    val message = viewModel.notice ?: return
    LaunchedEffect(message) {
        delay(NOTICE_MS)
        viewModel.notice = null
    }
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 20.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = stringResource(message),
            style = Antresol.typography.body,
            modifier = Modifier
                .background(AntresolColors.Glass, AntresolShapes.pill)
                .padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

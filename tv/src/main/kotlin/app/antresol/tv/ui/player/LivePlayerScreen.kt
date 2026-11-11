package app.antresol.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.channels.programmeArt
import app.antresol.ui.channels.ProgrammeStill
import app.antresol.tv.ui.components.listRowColors
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.RoundIconButton
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.tv.ui.components.MenuOption
import app.antresol.tv.ui.channels.filterTitle
import app.antresol.tv.ui.channels.LiveProgressBar
import app.antresol.tv.ui.channels.KeyHint
import app.antresol.tv.ui.channels.FavoriteMark
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.core.channels.ChannelFilter
import androidx.tv.material3.Surface
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
import androidx.tv.material3.Text
import app.antresol.core.channels.Channels
import app.antresol.core.channels.LiveStream
import app.antresol.core.channels.LiveTvState
import app.antresol.core.channels.MiniGuide
import app.antresol.core.model.Channel
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.R
import app.antresol.ui.channels.LiveBadge
import app.antresol.ui.channels.programClock
import app.antresol.ui.channels.rememberNowMs
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.playback.LiveMediaSession
import app.antresol.ui.playback.LivePlayerViewModel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.delay

private const val BANNER_HIDE_MS = 5_000L
private const val ACTIONS_HIDE_MS = 8_000L
private const val GUIDE_HIDE_MS = 15_000L

/** Width of the channel list panel on the left (TiviMate). */
private val GuidePanelWidth = 380.dp

/**
 * Why the banner is up: a channel switch ([Zap], info only), or OK ([Actions]: info with the
 * favourite, audio, subtitles and channel-list buttons focused).
 */
private enum class Banner { Hidden, Zap, Actions }

/** A track picker over the player. */
private enum class TrackPanel { Audio, Subtitles }

/**
 * A channel live, full screen (Android TV Live Channels / TiviMate). Up/Down (and Channel+/−, Page
 * Up/Down) switch to the next/previous channel of the list the player was opened from, by number,
 * wrapping; the previous channel's last frame holds, dimmed, until the new one draws. The info bar
 * (number · name, the programme with its progress, what is next) slides in on every switch and
 * fades after 5 s. OK shows it with its buttons: favourite, audio, subtitles, channel list. Left
 * (or the list button) opens the channel list on the left, focus on the channel on; OK switches.
 * Back closes a panel, then the bar, then leaves. Live: no seeking, no pause, nothing remembered.
 */
@Composable
fun LivePlayerScreen(viewModel: LivePlayerViewModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    val context = LocalContext.current
    DisposableEffect(viewModel) {
        val session = LiveMediaSession.build(context, viewModel.player)
        onDispose { session.release() }
    }
    val state = viewModel.state
    val stream = state.stream
    val failed = stream is LiveStream.Failed
    var banner by remember { mutableStateOf(Banner.Zap) }
    var guideOpen by remember { mutableStateOf(false) }
    var trackPanel by remember { mutableStateOf<TrackPanel?>(null) }
    var interactions by remember { mutableIntStateOf(0) }
    // The OK press that opened something must not also act on the focused button on release.
    var swallowCenterUp by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    val actionsFocus = remember { FocusRequester() }
    val panelOpen = guideOpen || trackPanel != null

    LaunchedEffect(failed, guideOpen, trackPanel, banner) {
        when {
            trackPanel != null -> Unit // The panel takes focus itself.
            guideOpen -> guideFocus.tryRequestFocus()
            failed -> retryFocus.tryRequestFocus()
            banner == Banner.Actions -> actionsFocus.tryRequestFocus()
            else -> rootFocus.tryRequestFocus()
        }
    }
    LaunchedEffect(banner, interactions, viewModel.isPlaying, state.channelId, panelOpen) {
        if (banner != Banner.Hidden && viewModel.isPlaying && !panelOpen) {
            delay(if (banner == Banner.Actions) ACTIONS_HIDE_MS else BANNER_HIDE_MS)
            banner = Banner.Hidden
        }
    }
    LaunchedEffect(guideOpen, interactions) {
        if (guideOpen) {
            delay(GUIDE_HIDE_MS)
            guideOpen = false
        }
    }
    BackHandler(enabled = guideOpen) { guideOpen = false }
    BackHandler(enabled = !panelOpen && banner != Banner.Hidden && viewModel.isPlaying) { banner = Banner.Hidden }

    val controls = object : LiveControls {
        override val panelOpen get() = panelOpen
        override val banner get() = banner
        override val failed get() = failed

        override fun zap(step: Int) {
            if (viewModel.zap(step)) {
                guideOpen = false
                if (banner != Banner.Actions) banner = Banner.Zap
            }
        }

        override fun showActions() {
            banner = Banner.Actions
            swallowCenterUp = true
        }

        override fun showInfo() {
            if (banner == Banner.Hidden) banner = Banner.Zap
        }

        override fun openGuide() {
            if (state.listed.isEmpty()) return
            guideOpen = true
            banner = Banner.Hidden
        }

        override fun touched() {
            interactions++
        }

        override fun swallowCenterUp(): Boolean {
            val swallow = swallowCenterUp
            swallowCenterUp = false
            return swallow
        }
    }
    val bannerShown = !guideOpen && (banner != Banner.Hidden || !viewModel.isPlaying)
    val holding = viewModel.awaitingPicture && viewModel.hasPicture
    val scrim by animateFloatAsState(if (holding) 0.45f else 0f, tween(AntresolMotion.MEDIUM_MS), label = "held-frame")

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event -> handleLiveKey(event, controls) }
            .focusRequester(rootFocus)
            .focusable(),
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
            enter = slideInVertically(tween(AntresolMotion.MEDIUM_MS)) { it / 3 } + fadeIn(tween(AntresolMotion.MEDIUM_MS)),
            exit = slideOutVertically(tween(AntresolMotion.MEDIUM_MS)) { it / 4 } + fadeOut(tween(AntresolMotion.MEDIUM_MS)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ChannelBanner(
                viewModel = viewModel,
                actions = banner == Banner.Actions,
                actionsFocus = actionsFocus,
                onGuide = { controls.openGuide() },
                onTracks = { trackPanel = it },
                onTouch = { interactions++ },
            )
        }
        AnimatedVisibility(
            visible = guideOpen,
            enter = slideInHorizontally(tween(AntresolMotion.MEDIUM_MS)) { -it } + fadeIn(tween(AntresolMotion.MEDIUM_MS)),
            exit = slideOutHorizontally(tween(AntresolMotion.MEDIUM_MS)) { -it } + fadeOut(tween(AntresolMotion.MEDIUM_MS)),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            ChannelListPanel(
                guide = viewModel.miniGuide,
                title = filterTitle(state.filter),
                currentId = state.channelId,
                startFocus = guideFocus,
                onTune = { channel ->
                    viewModel.tune(channel.id)
                    guideOpen = false
                    banner = Banner.Zap
                },
                onClose = { guideOpen = false },
            )
        }
        when (stream) {
            is LiveStream.Starting -> StartingView(showText = !viewModel.hasPicture || stream.attempt > 0)
            is LiveStream.Ready -> if (viewModel.awaitingPicture || viewModel.isBuffering) Spinner()
            is LiveStream.Failed -> FailedView(stream.message, onRetry = viewModel::retry, retryFocus = retryFocus)
        }
        trackPanel?.let { panel ->
            LiveTrackPanel(panel, viewModel, onDismiss = { trackPanel = null })
            BackHandler { trackPanel = null }
        }
    }
}

/** What the player's keys act on. */
private interface LiveControls {
    val panelOpen: Boolean
    val banner: Banner
    val failed: Boolean

    fun zap(step: Int)

    fun showActions()

    fun showInfo()

    fun openGuide()

    fun touched()

    fun swallowCenterUp(): Boolean
}

private val CenterKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * Channel keys switch channels everywhere but in a panel. Up/Down switch (also while the buttons are
 * up: Left/Right and OK belong to them). With nothing up, OK shows the bar with its buttons, Left
 * opens the channel list, Menu the audio choices; other keys show the info. Back goes to the
 * [BackHandler]s.
 */
private fun handleLiveKey(event: KeyEvent, controls: LiveControls): Boolean {
    val down = event.type == KeyEventType.KeyDown
    if (down) controls.touched()
    if (event.key == Key.Back) return false
    if (event.key in CenterKeys && event.type == KeyEventType.KeyUp && controls.swallowCenterUp()) return true
    if (controls.panelOpen) return false
    val channelStep = when (event.key) {
        Key.ChannelUp, Key.PageUp, Key.MediaNext, Key.DirectionUp -> 1
        Key.ChannelDown, Key.PageDown, Key.MediaPrevious, Key.DirectionDown -> -1
        else -> 0
    }
    if (channelStep != 0) {
        if (down) controls.zap(channelStep)
        return true
    }
    // The error panel's Try Again takes OK; the bar's buttons take Left/Right/OK.
    if (controls.failed || controls.banner == Banner.Actions) return false
    when (event.key) {
        in CenterKeys -> {
            if (down) controls.showActions()
            return true
        }
        Key.DirectionLeft -> {
            if (down) controls.openGuide()
            return true
        }
    }
    if (down) controls.showInfo()
    return true
}

/**
 * The info bar at the bottom: the channel's number large with its name, the programme on air (still,
 * title, episode), its start and end around the live progress, what is next; with [actions] the
 * buttons favourite, audio, subtitles, channel list.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChannelBanner(
    viewModel: LivePlayerViewModel,
    actions: Boolean,
    actionsFocus: FocusRequester,
    onGuide: () -> Unit,
    onTracks: (TrackPanel) -> Unit,
    onTouch: () -> Unit,
) {
    val context = LocalContext.current
    val urls = LocalMediaUrls.current
    val nowMs = rememberNowMs(1_000L)
    val state = viewModel.state
    val channel = state.channel ?: return
    val (onAir, next) = state.onAir(nowMs)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black.copy(alpha = 0.65f), 1f to Color.Black.copy(alpha = 0.92f)))
            .padding(start = ScreenPaddingH, end = ScreenPaddingH, top = 80.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.width(132.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    channel.number.toString(),
                    style = Antresol.typography.heroTitle.copy(fontFeatureSettings = "tnum"),
                    color = AntresolColors.Label,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(channel.name, style = Antresol.typography.title, color = AntresolColors.LabelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (channel.favorite) FavoriteMark()
                }
            }
            ProgrammeStill(
                url = urls?.image(programmeArt(onAir)),
                shape = AntresolShapes.smallCard,
                modifier = Modifier.width(148.dp).aspectRatio(AntresolMotion.BACKDROP_ASPECT),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LiveBadge()
                Text(
                    onAir?.title ?: stringResource(R.string.tv_nothing),
                    style = Antresol.typography.pageTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onAir != null && onAir.subtitle.isNotBlank()) {
                    Text(onAir.subtitle, style = Antresol.typography.body, color = AntresolColors.LabelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onAir != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(programClock(context, onAir.start), style = Antresol.typography.caption)
                    ProgressBar(fraction = Channels.progress(onAir, nowMs), modifier = Modifier.weight(1f))
                    Text(programClock(context, onAir.end), style = Antresol.typography.caption)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (next != null) {
                    Text(
                        stringResource(R.string.tv_next_short, programClock(context, next.start), Channels.programLine(next)),
                        style = Antresol.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (!actions) {
                    KeyHint("OK", stringResource(R.string.tv_audio_subtitles) + " · " + stringResource(R.string.tv_favorite))
                    Spacer(Modifier.width(20.dp))
                    if (state.listed.size > 1) KeyHint("◀", stringResource(R.string.tv_channel_list))
                }
            }
        }
        if (actions) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup(),
            ) {
                val favorite = channel.favorite
                RoundIconButton(
                    icon = if (favorite) AntresolIcons.StarFilled else AntresolIcons.Star,
                    contentDescription = stringResource(if (favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add),
                    onClick = { onTouch(); viewModel.toggleFavorite() },
                    size = 46.dp,
                    modifier = Modifier.focusRequester(actionsFocus),
                )
                if (viewModel.audioOptions.isNotEmpty()) {
                    RoundIconButton(AntresolIcons.Audio, stringResource(R.string.detail_audio), { onTouch(); onTracks(TrackPanel.Audio) }, size = 46.dp)
                }
                if (viewModel.subtitleOptions.isNotEmpty()) {
                    RoundIconButton(AntresolIcons.Subtitles, stringResource(R.string.detail_subtitles), { onTouch(); onTracks(TrackPanel.Subtitles) }, size = 46.dp)
                }
                if (state.listed.size > 1) {
                    RoundIconButton(AntresolIcons.List, stringResource(R.string.tv_channel_list), onGuide, size = 46.dp)
                }
            }
        }
    }
}

/**
 * The channel list over the left of the picture (TiviMate): the list the player was opened from by
 * number — number, name with the star, the programme and its progress — focus on the channel on.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChannelListPanel(
    guide: MiniGuide,
    title: String,
    currentId: Long,
    startFocus: FocusRequester,
    onTune: (Channel) -> Unit,
    onClose: () -> Unit,
) {
    val nowMs = rememberNowMs()
    val context = LocalContext.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (guide.currentIndex - 3).coerceAtLeast(0))
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(GuidePanelWidth + ScreenPaddingH)
            .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.85f to Color.Black.copy(alpha = 0.8f), 1f to Color.Transparent))
            .padding(start = ScreenPaddingH - 12.dp, top = 36.dp)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = Antresol.typography.sectionTitle, modifier = Modifier.padding(start = 12.dp))
        VerticalShelfScrolling(leadingFraction = 0.25f, trailingFraction = 0.25f) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .width(GuidePanelWidth)
                    .focusRestorer { startFocus },
            ) {
                itemsIndexed(guide.channels, key = { _, channel -> channel.id }) { index, channel ->
                    val program = Channels.showing(channel, nowMs)
                    var focused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { onTune(channel) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == guide.currentIndex) Modifier.focusRequester(startFocus) else Modifier)
                            .onFocusChanged { focused = it.isFocused },
                        shape = ClickableSurfaceDefaults.shape(AntresolShapes.control),
                        colors = listRowColors(),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    ) {
                        val content = LocalContentColor.current
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                channel.number.toString(),
                                style = Antresol.typography.title.copy(fontFeatureSettings = "tnum"),
                                color = if (focused) content else AntresolColors.LabelSecondary,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(28.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (channel.id == currentId) {
                                        Box(Modifier.size(7.dp).background(AntresolColors.Red, CircleShape))
                                    }
                                    Text(channel.name, style = Antresol.typography.title, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (channel.favorite) FavoriteMark(tint = if (focused) content else AntresolColors.Orange)
                                }
                                Text(
                                    program?.let(Channels::programLine) ?: stringResource(R.string.tv_nothing),
                                    style = Antresol.typography.caption,
                                    color = content.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (program != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                                        LiveProgressBar(Channels.progress(program, nowMs), content, Modifier.weight(1f))
                                        Text(stringResource(R.string.tv_until, programClock(context, program.end)), style = Antresol.typography.badge, color = content.copy(alpha = 0.75f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** «Звук» or «Субтитры» for the programme on air: the renditions the server names for it. */
@Composable
private fun LiveTrackPanel(panel: TrackPanel, viewModel: LivePlayerViewModel, onDismiss: () -> Unit) {
    val selection = viewModel.trackSelection
    val options = when (panel) {
        TrackPanel.Audio -> viewModel.audioOptions.map { track ->
            MenuOption(track.label.ifBlank { track.name }, track.slot == selection.audio?.slot) { viewModel.selectAudio(track) }
        }
        TrackPanel.Subtitles -> listOf(
            MenuOption(stringResource(R.string.detail_subtitles_off), selection.subtitles == null) { viewModel.selectSubtitles(null) },
        ) + viewModel.subtitleOptions.map { track ->
            MenuOption(track.label.ifBlank { track.name }, track.slot == selection.subtitles?.slot) { viewModel.selectSubtitles(track) }
        }
    }
    OptionsPanel(
        title = stringResource(if (panel == TrackPanel.Audio) R.string.detail_audio else R.string.detail_subtitles),
        options = options,
        onDismiss = onDismiss,
    )
}

@Composable
private fun StartingView(showText: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Spinner()
        if (showText) {
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

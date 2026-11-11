package app.antresol.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
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
import app.antresol.tv.ui.channels.ChannelCard
import app.antresol.tv.ui.components.HorizontalRowScrolling
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelLogoBadge
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
private const val GUIDE_HIDE_MS = 15_000L

/** Width of a channel card in the mini-guide: five fit the screen, the picture stays in view above. */
private val GuideCardWidth = 172.dp

/** Why the channel banner is up: a channel switch, or OK / another key asking what is on. */
private enum class Banner { Hidden, Zap, Info }

/**
 * A channel live, full screen. Up/Down (and Channel+/−, Page Up/Down, Next/Previous) switch to the
 * next or previous channel by number, wrapping; the previous channel's last frame holds, dimmed,
 * until the new one draws. The banner (logo, channel, «Live», the programme with its progress, what
 * is next) slides in on every switch and on OK, and fades after 5 s. OK with the banner up (or Down
 * after OK) opens the mini-guide: the section's channels in a row, focus on the one on; OK switches.
 * Back hides the guide, then the banner, then leaves. Live: no seeking, no pause.
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
    var banner by remember { mutableStateOf(Banner.Info) }
    var guideOpen by remember { mutableStateOf(false) }
    var interactions by remember { mutableIntStateOf(0) }
    // The OK press that opened the guide must not also switch to the focused channel on release.
    var swallowCenterUp by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }

    LaunchedEffect(failed, guideOpen) {
        when {
            guideOpen -> guideFocus.tryRequestFocus()
            failed -> retryFocus.tryRequestFocus()
            else -> rootFocus.tryRequestFocus()
        }
    }
    LaunchedEffect(banner, interactions, viewModel.isPlaying, state.channelId, guideOpen) {
        if (banner != Banner.Hidden && viewModel.isPlaying && !guideOpen) {
            delay(BANNER_HIDE_MS)
            banner = Banner.Hidden
        }
    }
    LaunchedEffect(guideOpen, interactions) {
        if (guideOpen) {
            delay(GUIDE_HIDE_MS)
            guideOpen = false
        }
    }
    BackHandler(enabled = guideOpen) {
        guideOpen = false
        banner = Banner.Info
    }
    BackHandler(enabled = !guideOpen && banner != Banner.Hidden && viewModel.isPlaying) { banner = Banner.Hidden }

    val controls = object : LiveControls {
        override val guideOpen get() = guideOpen
        override val banner get() = banner
        override val failed get() = failed

        override fun zap(step: Int) {
            if (viewModel.zap(step)) {
                guideOpen = false
                banner = Banner.Zap
            }
        }

        override fun showInfo() {
            if (banner == Banner.Hidden) banner = Banner.Info
        }

        override fun openGuide() {
            if (state.channels.isEmpty()) return
            guideOpen = true
            swallowCenterUp = true
        }

        override fun closeGuide() {
            guideOpen = false
            banner = Banner.Info
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
            ChannelBanner(state, showGuideHint = state.channels.size > 1)
        }
        AnimatedVisibility(
            visible = guideOpen,
            enter = slideInVertically(tween(AntresolMotion.MEDIUM_MS)) { it / 2 } + fadeIn(tween(AntresolMotion.MEDIUM_MS)),
            exit = slideOutVertically(tween(AntresolMotion.MEDIUM_MS)) { it / 2 } + fadeOut(tween(AntresolMotion.MEDIUM_MS)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            MiniGuideStrip(
                guide = viewModel.miniGuide,
                currentId = state.channelId,
                startFocus = guideFocus,
                onTune = { channel ->
                    viewModel.tune(channel.id)
                    guideOpen = false
                    banner = Banner.Zap
                },
            )
        }
        when (stream) {
            is LiveStream.Starting -> StartingView(showText = !viewModel.hasPicture || stream.attempt > 0)
            is LiveStream.Ready -> if (viewModel.awaitingPicture || viewModel.isBuffering) Spinner()
            is LiveStream.Failed -> FailedView(stream.message, onRetry = viewModel::retry, retryFocus = retryFocus)
        }
    }
}

/** What the player's keys act on. */
private interface LiveControls {
    val guideOpen: Boolean
    val banner: Banner
    val failed: Boolean

    fun zap(step: Int)

    fun showInfo()

    fun openGuide()

    fun closeGuide()

    fun touched()

    fun swallowCenterUp(): Boolean
}

private val CenterKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * Channel keys switch channels everywhere. With the guide open, Up closes it and the rest moves
 * through the strip. Otherwise Up/Down switch, OK shows the banner and, with it up, opens the guide
 * (Down too after OK); other keys show the banner. Back goes to the [BackHandler]s.
 */
private fun handleLiveKey(event: KeyEvent, controls: LiveControls): Boolean {
    val down = event.type == KeyEventType.KeyDown
    if (down) controls.touched()
    if (event.key == Key.Back) return false
    if (event.key in CenterKeys && event.type == KeyEventType.KeyUp && controls.swallowCenterUp()) return true
    val channelStep = when (event.key) {
        Key.ChannelUp, Key.PageUp, Key.MediaNext -> 1
        Key.ChannelDown, Key.PageDown, Key.MediaPrevious -> -1
        else -> 0
    }
    if (channelStep != 0) {
        if (down) controls.zap(channelStep)
        return true
    }
    if (controls.guideOpen) {
        if (event.key == Key.DirectionUp) {
            if (down) controls.closeGuide()
            return true
        }
        return false
    }
    when (event.key) {
        Key.DirectionUp -> {
            if (down) controls.zap(1)
            return true
        }
        Key.DirectionDown -> {
            if (down) {
                if (controls.banner == Banner.Info && !controls.failed) controls.openGuide() else controls.zap(-1)
            }
            return true
        }
    }
    // The error panel's Try Again takes OK.
    if (controls.failed) return false
    if (event.key in CenterKeys) {
        if (down) {
            if (controls.banner == Banner.Hidden) controls.showInfo() else controls.openGuide()
        }
        return true
    }
    if (down) controls.showInfo()
    return true
}

/**
 * The channel banner at the bottom: logo, «10 · Comedy» with the live dot, the programme on air,
 * its start and end around the live progress, and what is next.
 */
@Composable
private fun ChannelBanner(state: LiveTvState, showGuideHint: Boolean) {
    val context = LocalContext.current
    val nowMs = rememberNowMs(1_000L)
    val channel = state.channel ?: return
    val (onAir, next) = state.onAir(nowMs)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.9f)))
            .padding(start = ScreenPaddingH, end = ScreenPaddingH, top = 96.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ChannelLogoBadge(name = channel.name, logo = channel.logo, width = 116.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        stringResource(R.string.tv_channel_number, channel.number, channel.name),
                        style = Antresol.typography.title,
                        color = AntresolColors.LabelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LiveBadge()
                }
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
            if (showGuideHint) KeyHint(key = "OK", label = stringResource(R.string.tv_guide_hint))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onAir != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
                )
            }
        }
    }
}

/** «[OK] Channels»: a remote key and what it does, in the banner's corner. */
@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            key,
            style = Antresol.typography.badge,
            color = AntresolColors.Label,
            modifier = Modifier
                .border(1.dp, AntresolColors.LabelTertiary, AntresolShapes.badge)
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
        Text(label, style = Antresol.typography.caption, maxLines = 1)
    }
}

/** The mini-guide: the section's title and its channels in a row, focus starting at the one on. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MiniGuideStrip(guide: MiniGuide, currentId: Long, startFocus: FocusRequester, onTune: (Channel) -> Unit) {
    val nowMs = rememberNowMs()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = guide.startIndex)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color.Black.copy(alpha = 0.65f), 1f to Color.Black.copy(alpha = 0.92f)))
            .padding(top = 72.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            guide.category.ifEmpty { stringResource(R.string.nav_tv) },
            style = Antresol.typography.sectionTitle,
            modifier = Modifier.padding(horizontal = ScreenPaddingH),
        )
        HorizontalRowScrolling {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = ScreenPaddingH, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .trapHorizontalFocus()
                    .focusRestorer(),
            ) {
                itemsIndexed(guide.channels, key = { _, channel -> channel.id }) { index, channel ->
                    ChannelCard(
                        channel = channel,
                        nowMs = nowMs,
                        onClick = { onTune(channel) },
                        width = GuideCardWidth,
                        watching = channel.id == currentId,
                        showNext = false,
                        surfaceModifier = if (index == guide.startIndex) Modifier.focusRequester(startFocus) else Modifier,
                    )
                }
            }
        }
    }
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

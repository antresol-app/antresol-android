package app.antresol.tv.ui.player

import app.antresol.ui.channels.ChannelLogo
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
import androidx.tv.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import app.antresol.tv.ui.channels.PanelAction
import app.antresol.tv.ui.channels.KeyPanel
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
private const val PANEL_HIDE_MS = 15_000L

/** Width of the channel list panel on the left (TiviMate). */
private val GuidePanelWidth = 380.dp

/** Why the banner is up: a channel switch ([Zap], info only), or OK ([Actions]: info with its buttons). */
private enum class Banner { Hidden, Zap, Actions }

/** What is open over the picture besides the banner. */
private enum class Panel { None, Channels, Audio, Subtitles }

/** A button of the banner. */
private enum class LiveAction { Favorite, Audio, Subtitles, Channels }

/**
 * A channel live, full screen (Android TV Live Channels / TiviMate). Up/Down (and Channel+/−, Page
 * Up/Down) switch to the next/previous channel of the list the player was opened from, by number,
 * wrapping; the previous channel's last frame holds, dimmed, until the new one draws. The info bar
 * (number · name, the programme's still, title and progress, what is next) slides in on every
 * switch and fades after 5 s. OK opens the channel list on the left at the channel on (TiviMate;
 * Left too). Right or Menu shows the banner's buttons (favourite, audio, subtitles, channel list;
 * Left/Right pick, OK runs). Back closes a panel, then the bar, then leaves. The screen handles the
 * remote itself (no focus moves between overlays). Live: no seeking, no pause, nothing remembered.
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
    var panel by remember { mutableStateOf(Panel.None) }
    var actionIndex by remember { mutableIntStateOf(0) }
    var panelIndex by remember { mutableIntStateOf(0) }
    var interactions by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocus.tryRequestFocus() }

    val actions = buildList {
        add(LiveAction.Favorite)
        if (viewModel.audioOptions.isNotEmpty()) add(LiveAction.Audio)
        if (viewModel.subtitleOptions.isNotEmpty()) add(LiveAction.Subtitles)
        if (state.listed.size > 1) add(LiveAction.Channels)
    }
    val guide = viewModel.miniGuide
    val selection = viewModel.trackSelection
    val audio = viewModel.audioOptions
    val subtitles = viewModel.subtitleOptions

    LaunchedEffect(banner, interactions, viewModel.isPlaying, state.channelId, panel) {
        if (banner != Banner.Hidden && viewModel.isPlaying && panel == Panel.None) {
            delay(if (banner == Banner.Actions) ACTIONS_HIDE_MS else BANNER_HIDE_MS)
            banner = Banner.Hidden
        }
    }
    LaunchedEffect(panel, interactions) {
        if (panel != Panel.None) {
            delay(PANEL_HIDE_MS)
            panel = Panel.None
        }
    }
    BackHandler(enabled = panel != Panel.None) { panel = Panel.None }
    BackHandler(enabled = panel == Panel.None && banner != Banner.Hidden && viewModel.isPlaying) { banner = Banner.Hidden }

    fun zap(step: Int) {
        if (viewModel.zap(step)) {
            if (panel == Panel.Channels) panel = Panel.None
            if (banner != Banner.Actions) banner = Banner.Zap
        }
    }

    fun openPanel(next: Panel) {
        panel = next
        panelIndex = when (next) {
            Panel.Channels -> guide.currentIndex
            Panel.Audio -> audio.indexOfFirst { it.name == selection.audio?.name }.coerceAtLeast(0)
            Panel.Subtitles -> subtitles.indexOfFirst { it.name == selection.subtitles?.name } + 1
            Panel.None -> 0
        }
        if (next == Panel.Channels) banner = Banner.Hidden
    }

    fun runAction(action: LiveAction) {
        when (action) {
            LiveAction.Favorite -> viewModel.toggleFavorite()
            LiveAction.Audio -> openPanel(Panel.Audio)
            LiveAction.Subtitles -> openPanel(Panel.Subtitles)
            LiveAction.Channels -> openPanel(Panel.Channels)
        }
    }

    fun panelSize(): Int = when (panel) {
        Panel.Channels -> guide.channels.size
        Panel.Audio -> audio.size
        Panel.Subtitles -> subtitles.size + 1
        Panel.None -> 0
    }

    fun runPanel() {
        when (panel) {
            Panel.Channels -> guide.channels.getOrNull(panelIndex)?.let { channel ->
                viewModel.tune(channel.id)
                banner = Banner.Zap
            }
            Panel.Audio -> audio.getOrNull(panelIndex)?.let(viewModel::selectAudio)
            Panel.Subtitles -> viewModel.selectSubtitles(subtitles.getOrNull(panelIndex - 1))
            Panel.None -> Unit
        }
        panel = Panel.None
    }

    val onKey: (KeyEvent) -> Boolean = handler@{ event ->
        val down = event.type == KeyEventType.KeyDown
        val up = event.type == KeyEventType.KeyUp
        if (down) interactions++
        if (event.key == Key.Back) return@handler false
        val channelStep = when (event.key) {
            Key.ChannelUp, Key.PageUp, Key.MediaNext -> 1
            Key.ChannelDown, Key.PageDown, Key.MediaPrevious -> -1
            else -> 0
        }
        if (channelStep != 0) {
            if (down) zap(channelStep)
            return@handler true
        }
        if (panel != Panel.None) {
            when (event.key) {
                Key.DirectionUp -> if (down) panelIndex = (panelIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> if (down) panelIndex = (panelIndex + 1).coerceAtMost(panelSize() - 1)
                Key.DirectionRight -> if (down && panel == Panel.Channels) panel = Panel.None
                Key.DirectionLeft -> if (down && panel != Panel.Channels) panel = Panel.None
                in CenterKeys -> if (up) runPanel()
            }
            return@handler true
        }
        if (failed) {
            if (event.key in CenterKeys && up) viewModel.retry()
            return@handler true
        }
        when (event.key) {
            Key.DirectionUp -> if (down) zap(1)
            Key.DirectionDown -> if (down) zap(-1)
            Key.DirectionLeft -> if (down) {
                if (banner == Banner.Actions) actionIndex = (actionIndex - 1).coerceAtLeast(0) else openPanel(Panel.Channels)
            }
            // Right (or Menu) brings up the banner's buttons; Right moves through them.
            Key.DirectionRight -> if (down) {
                if (banner == Banner.Actions) {
                    actionIndex = (actionIndex + 1).coerceAtMost(actions.size - 1)
                } else {
                    banner = Banner.Actions
                    actionIndex = 0
                }
            }
            Key.Menu -> if (down) {
                banner = Banner.Actions
                actionIndex = 0
            }
            // OK, as in TiviMate: the channel list; with the buttons up, the selected button.
            in CenterKeys -> if (up) {
                if (banner == Banner.Actions) actions.getOrNull(actionIndex)?.let(::runAction) else openPanel(Panel.Channels)
            }
            else -> if (down && banner == Banner.Hidden) banner = Banner.Zap
        }
        true
    }

    val bannerShown = panel != Panel.Channels && (banner != Banner.Hidden || !viewModel.isPlaying)
    val holding = viewModel.awaitingPicture && viewModel.hasPicture
    val scrim by animateFloatAsState(if (holding) 0.45f else 0f, tween(AntresolMotion.MEDIUM_MS), label = "held-frame")

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent(onKey)
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
            ChannelBanner(viewModel, actions = if (banner == Banner.Actions && panel == Panel.None) actions else emptyList(), selected = actionIndex)
        }
        AnimatedVisibility(
            visible = panel == Panel.Channels,
            enter = slideInHorizontally(tween(AntresolMotion.MEDIUM_MS)) { -it } + fadeIn(tween(AntresolMotion.MEDIUM_MS)),
            exit = slideOutHorizontally(tween(AntresolMotion.MEDIUM_MS)) { -it } + fadeOut(tween(AntresolMotion.MEDIUM_MS)),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            ChannelListPanel(guide = guide, title = filterTitle(state.filter), currentId = state.channelId, selected = panelIndex)
        }
        when (stream) {
            is LiveStream.Starting -> StartingView(showText = !viewModel.hasPicture || stream.attempt > 0)
            is LiveStream.Ready -> if (viewModel.awaitingPicture || viewModel.isBuffering) Spinner()
            is LiveStream.Failed -> FailedView(stream.message)
        }
        if (panel == Panel.Audio || panel == Panel.Subtitles) {
            val options = if (panel == Panel.Audio) {
                audio.map { track -> PanelAction(track.label.ifBlank { track.name }, AntresolIcons.Check.takeIf { track.name == selection.audio?.name }) {} }
            } else {
                listOf(PanelAction(stringResource(R.string.detail_subtitles_off), AntresolIcons.Check.takeIf { selection.subtitles == null }) {}) +
                    subtitles.map { track -> PanelAction(track.label.ifBlank { track.name }, AntresolIcons.Check.takeIf { track.name == selection.subtitles?.name }) {} }
            }
            KeyPanel(
                title = stringResource(if (panel == Panel.Audio) R.string.detail_audio else R.string.detail_subtitles),
                actions = options,
                selected = panelIndex,
            )
        }
    }
}

private val CenterKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * The info bar at the bottom: the channel's number large with its name, the programme on air (still,
 * title, episode), its start and end around the live progress, what is next; with [actions] the
 * buttons (favourite, audio, subtitles, channel list), [selected] one white with its name beside.
 */
@Composable
private fun ChannelBanner(viewModel: LivePlayerViewModel, actions: List<LiveAction>, selected: Int) {
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
            val logo = urls?.image(channel.logoUrl)
            // With a logo the column widens by the logo's box, so the name keeps its two lines.
            Column(Modifier.width(if (logo != null) 132.dp + BannerLogoHeight * 1.5f + 12.dp else 132.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        channel.number.toString(),
                        style = Antresol.typography.heroTitle.copy(fontFeatureSettings = "tnum"),
                        color = AntresolColors.Label,
                        maxLines = 1,
                    )
                    ChannelLogo(logo, height = BannerLogoHeight)
                }
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
                if (actions.isEmpty()) {
                    if (state.listed.size > 1) {
                        KeyHint("OK", stringResource(R.string.tv_channel_list))
                        Spacer(Modifier.width(20.dp))
                    }
                    KeyHint("▶", stringResource(R.string.tv_ok_actions))
                }
            }
        }
        if (actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                actions.forEachIndexed { index, action ->
                    ActionButton(
                        icon = when (action) {
                            LiveAction.Favorite -> if (channel.favorite) AntresolIcons.StarFilled else AntresolIcons.Star
                            LiveAction.Audio -> AntresolIcons.Audio
                            LiveAction.Subtitles -> AntresolIcons.Subtitles
                            LiveAction.Channels -> AntresolIcons.List
                        },
                        selected = index == selected,
                    )
                }
                actions.getOrNull(selected)?.let { action ->
                    Text(
                        when (action) {
                            LiveAction.Favorite -> stringResource(if (channel.favorite) R.string.tv_favorite_remove else R.string.tv_favorite_add)
                            LiveAction.Audio -> stringResource(R.string.detail_audio) + (viewModel.trackSelection.audio?.let { " · " + it.label.ifBlank { it.name } } ?: "")
                            LiveAction.Subtitles -> stringResource(R.string.detail_subtitles) + " · " +
                                (viewModel.trackSelection.subtitles?.let { it.label.ifBlank { it.name } } ?: stringResource(R.string.detail_subtitles_off))
                            LiveAction.Channels -> stringResource(R.string.tv_channel_list)
                        },
                        style = Antresol.typography.bodyEmphasis,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

/** The height of the logo box beside the banner's channel number. */
private val BannerLogoHeight = 36.dp

/** A round banner button: white with a black glyph when selected, dark glass otherwise. */
@Composable
private fun ActionButton(icon: ImageVector, selected: Boolean) {
    Box(
        Modifier
            .size(46.dp)
            .background(if (selected) AntresolColors.Label else AntresolColors.Glass, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) AntresolColors.LabelInverse else AntresolColors.Label, modifier = Modifier.size(21.dp))
    }
}

/**
 * The channel list over the left of the picture (TiviMate): the list the player was opened from by
 * number — number, name with the star, the programme and its progress — [selected] in white; the
 * channel on screen has the live dot.
 */
@Composable
private fun ChannelListPanel(guide: MiniGuide, title: String, currentId: Long, selected: Int) {
    val urls = LocalMediaUrls.current
    val nowMs = rememberNowMs()
    val context = LocalContext.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 3).coerceAtLeast(0))
    LaunchedEffect(selected) {
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (selected !in visible.drop(1).dropLast(1)) listState.animateScrollToItem((selected - 3).coerceAtLeast(0))
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(GuidePanelWidth + ScreenPaddingH)
            .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.85f to Color.Black.copy(alpha = 0.8f), 1f to Color.Transparent))
            .padding(start = ScreenPaddingH - 12.dp, top = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = Antresol.typography.sectionTitle, modifier = Modifier.padding(start = 12.dp))
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.width(GuidePanelWidth),
        ) {
            itemsIndexed(guide.channels, key = { _, channel -> channel.id }) { index, channel ->
                val program = Channels.showing(channel, nowMs)
                val isSelected = index == selected
                val content = if (isSelected) AntresolColors.LabelInverse else AntresolColors.Label
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) AntresolColors.Label else Color.Transparent, AntresolShapes.control)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        channel.number.toString(),
                        style = Antresol.typography.title.copy(fontFeatureSettings = "tnum"),
                        color = if (isSelected) content else AntresolColors.LabelSecondary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(28.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (channel.id == currentId) {
                                Box(Modifier.size(7.dp).background(AntresolColors.Red, CircleShape))
                            }
                            ChannelLogo(urls?.image(channel.logoUrl), height = 20.dp)
                            Text(channel.name, style = Antresol.typography.title, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (channel.favorite) FavoriteMark(tint = if (isSelected) content else AntresolColors.Orange)
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

/** The channel can't be shown: the reason and «Повторить» (OK). */
@Composable
private fun FailedView(message: String?) {
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
            KeyHint("OK", stringResource(R.string.action_retry), Modifier.padding(top = 8.dp))
        }
    }
}

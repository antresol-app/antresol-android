package app.antresol.tv.ui.shell

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.tv.R as TvR
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.choicePillColors
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.LogoTile
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.delay

/**
 * The web app's top bar: brand on the left, the centred glass pill of tabs (white = the focused tab,
 * grey = the current one; resting focus on a tab opens it), and on the right the now-playing chip
 * (while music or an audiobook is loaded) and the profile avatar, which opens Settings. Entering the pill from below lands on the current tab.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopNav(
    selected: NavTab?,
    /** The tab the current page was reached from (detail, grid, Songs), shown dimmed; Up lands there. */
    contextTab: NavTab?,
    userName: String,
    avatar: String,
    /** The profile's photo, resolved; null draws the letter. */
    photoUrl: String?,
    nowPlaying: String?,
    nowPlayingActive: Boolean,
    onSelect: (NavTab) -> Unit,
    onNowPlaying: () -> Unit,
    onSettings: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    /** Down from the nav: focuses the current page (its remembered element); false when there is none. */
    onEnterContent: (FocusManager) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val requesters = remember { NavTab.entries.associateWith { FocusRequester() } }
    // Focus selects (the Android TV and Google TV launchers): the tab focus rests on for NAV_SELECT_DELAY_MS opens,
    // so passing over tabs loads none of them. The tab the page belongs to never re-opens: selecting it again would
    // take a detail page back to the tab's root.
    var focusedTab by remember { mutableStateOf<NavTab?>(null) }
    val current = selected ?: contextTab
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(focusedTab, current) {
        val tab = focusedTab ?: return@LaunchedEffect
        if (tab == current) return@LaunchedEffect
        delay(NAV_SELECT_DELAY_MS)
        select(tab)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPaddingH, vertical = 18.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            // The page spans the whole screen behind the nav, so geometric search never finds it
            // below: Down enters the page's focus group, which restores its last focused element.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) onEnterContent(focusManager)
                true
            },
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogoTile(size = 30.dp)
            Text(stringResource(R.string.brand_name), style = Antresol.typography.title.copy(fontSize = 17.sp))
        }
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .background(AntresolColors.Glass, AntresolShapes.pill)
                .border(1.dp, AntresolColors.GlassBorder, AntresolShapes.pill)
                .padding(4.dp)
                // Coming from the content, land on the current tab (not the nearest pill).
                .focusProperties { enter = { requesters.getValue(selected ?: contextTab ?: NavTab.Home) } }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NavTab.entries.forEach { tab ->
                NavPill(
                    tab = tab,
                    selected = tab == selected,
                    context = selected == null && tab == contextTab,
                    onClick = { onSelect(tab) },
                    modifier = Modifier
                        .focusRequester(requesters.getValue(tab))
                        .onFocusChanged { state ->
                            if (state.isFocused) focusedTab = tab else if (focusedTab == tab) focusedTab = null
                        },
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (nowPlaying != null) NowPlayingChip(nowPlaying, nowPlayingActive, onNowPlaying)
            AvatarButton(userName, avatar, photoUrl, onSettings)
        }
    }
}

/** How long focus rests on a tab before it opens (focus selects). */
internal const val NAV_SELECT_DELAY_MS = 300L

/** The glass chip with the playing song or audiobook; opens Now Playing. */
@Composable
private fun NowPlayingChip(title: String, active: Boolean, onClick: () -> Unit) {
    val label = stringResource(TvR.string.now_playing)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(32.dp)
            .widthIn(max = 190.dp)
            .semantics { contentDescription = label },
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AntresolColors.Glass,
            contentColor = AntresolColors.Label,
            focusedContainerColor = AntresolColors.Label,
            focusedContentColor = AntresolColors.LabelInverse,
            pressedContainerColor = AntresolColors.LabelSecondary,
            pressedContentColor = AntresolColors.LabelInverse,
        ),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, AntresolColors.GlassBorder), shape = AntresolShapes.pill)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            EqualizerBars(active, LocalContentColor.current)
            Text(
                title,
                style = Antresol.typography.caption.copy(fontSize = 13.sp),
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Three bars that bounce while audio plays and rest at fixed, uneven heights when paused (flat bars read as "…"). */
@Composable
private fun EqualizerBars(active: Boolean, color: Color) {
    // Paused, the bars are still: no infinite animation then, which would ask for a frame 60 times a second for as
    // long as the chip shows (the whole time music is paused).
    if (active) BouncingBars(color) else Bars(color) { index -> PausedBars[index] }
}

@Composable
private fun BouncingBars(color: Color) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    // Uneven lows and fast-forwarded start offsets keep the bars at different heights from the first frame.
    val phases = listOf(0, 180, 360).mapIndexed { index, offsetMs ->
        transition.animateFloat(
            initialValue = PausedBars[index] * 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(420),
                RepeatMode.Reverse,
                initialStartOffset = StartOffset(offsetMs, StartOffsetType.FastForward),
            ),
            label = "bar",
        )
    }
    Bars(color) { index -> phases[index].value }
}

@Composable
private fun Bars(color: Color, height: (Int) -> Float) {
    Canvas(Modifier.size(width = 12.dp, height = 12.dp)) {
        val barWidth = size.width / 5
        for (index in 0..2) {
            val barHeight = size.height * height(index)
            drawRect(
                color = color,
                topLeft = Offset(index * 2 * barWidth, size.height - barHeight),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

private val PausedBars = listOf(0.5f, 1f, 0.7f)

@Composable
private fun AvatarButton(userName: String, avatar: String, photoUrl: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(34.dp)
            .semantics { contentDescription = userName },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, AntresolColors.Label), inset = 2.dp, shape = CircleShape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.3f), 10.dp)),
    ) {
        ProfileAvatar(name = userName, avatar = avatar, size = 34.dp, photoUrl = photoUrl)
    }
}

@Composable
private fun NavPill(
    tab: NavTab,
    selected: Boolean,
    context: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only the focused tab is white ([choicePillColors]); the current tab, and on a page below a tab (detail, grid)
    // that tab, keep the grey fill, so the user sees where they are and never mistakes it for focus.
    Surface(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.pill),
        colors = choicePillColors(selected || context),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(if (tab.iconOnly) PaddingValues(horizontal = 12.dp) else PaddingValues(horizontal = 16.dp)),
        ) {
            if (tab.iconOnly) {
                Icon(
                    imageVector = AntresolIcons.Search,
                    contentDescription = stringResource(tab.label),
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(17.dp),
                )
            } else {
                Text(
                    text = stringResource(tab.label),
                    style = Antresol.typography.caption.copy(fontSize = 13.sp),
                    color = LocalContentColor.current,
                    maxLines = 1,
                )
            }
        }
    }
}

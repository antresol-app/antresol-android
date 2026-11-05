package app.antresol.tv.ui.shell

import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
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
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.LogoTile
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * The web app's top bar: brand on the left, the centred glass pill of tabs (white pill = current
 * tab), and on the right the now-playing chip (while music or an audiobook is loaded) and the
 * profile avatar, which opens Settings. Entering the pill from below lands on the current tab.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopNav(
    selected: NavTab?,
    /** The tab the current page was reached from (detail, grid, Songs), shown dimmed; Up lands there. */
    contextTab: NavTab?,
    userName: String,
    avatar: String,
    nowPlaying: String?,
    nowPlayingActive: Boolean,
    onSelect: (NavTab) -> Unit,
    onNowPlaying: () -> Unit,
    onSettings: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    /** The current page's focus requester, if a page is attached. */
    contentFocus: () -> FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val requesters = remember { NavTab.entries.associateWith { FocusRequester() } }
    var pillFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPaddingH, vertical = 18.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            // The page spans the whole screen behind the nav, so geometric search never finds it
            // below: Down enters the page's focus group, which restores its last focused element.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    try {
                        val page = contentFocus()
                        if (page != null) {
                            page.requestFocus()
                            focusManager.moveFocus(FocusDirection.Enter)
                        }
                    } catch (_: IllegalStateException) {
                        // No page attached yet.
                    }
                }
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
                .onFocusChanged { pillFocused = it.hasFocus }
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
                    rowFocused = pillFocused,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.focusRequester(requesters.getValue(tab)),
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (nowPlaying != null) NowPlayingChip(nowPlaying, nowPlayingActive, onNowPlaying)
            AvatarButton(userName, avatar, onSettings)
        }
    }
}

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

/** Three bars that bounce while audio plays and rest flat when paused. */
@Composable
private fun EqualizerBars(active: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val phases = listOf(0, 180, 360).map { delayMs ->
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(420, delayMillis = delayMs), RepeatMode.Reverse),
            label = "bar",
        )
    }
    Canvas(Modifier.size(width = 12.dp, height = 12.dp)) {
        val barWidth = size.width / 5
        phases.forEachIndexed { index, phase ->
            val height = size.height * if (active) phase.value else 0.3f
            drawRect(
                color = color,
                topLeft = Offset(index * 2 * barWidth, size.height - height),
                size = Size(barWidth, height),
            )
        }
    }
}

@Composable
private fun AvatarButton(userName: String, avatar: String, onClick: () -> Unit) {
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
        ProfileAvatar(name = userName, avatar = avatar, size = 34.dp)
    }
}

@Composable
private fun NavPill(
    tab: NavTab,
    selected: Boolean,
    context: Boolean,
    rowFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Outside the pill the current tab is white; while the pill has focus the focused tab is white
    // and the current one keeps a grey fill so the user still sees where they are. On a page below a
    // tab (detail, grid) that tab keeps the grey fill.
    val restContainer = when {
        selected && !rowFocused -> AntresolColors.Label
        selected || context -> AntresolColors.FillPrimary
        else -> Color.Transparent
    }
    val restContent = when {
        selected && !rowFocused -> AntresolColors.LabelInverse
        selected || context -> AntresolColors.Label
        else -> AntresolColors.LabelSecondary
    }
    Surface(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = restContainer,
            contentColor = restContent,
            focusedContainerColor = AntresolColors.Label,
            focusedContentColor = AntresolColors.LabelInverse,
            pressedContainerColor = AntresolColors.LabelSecondary,
            pressedContentColor = AntresolColors.LabelInverse,
        ),
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

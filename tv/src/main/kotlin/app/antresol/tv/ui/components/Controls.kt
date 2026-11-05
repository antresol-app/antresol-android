package app.antresol.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/** One choice of an [OptionsPanel]. */
data class MenuOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

/**
 * An opaque panel on the right, over a dimmed page, with a list of choices; picking one applies it
 * and closes the panel. Focus starts on [focusIndex] (default: the selected choice) and stays in the
 * panel until Back ([onDismiss] by the caller).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OptionsPanel(
    title: String,
    options: List<MenuOption>,
    onDismiss: () -> Unit,
    note: String? = null,
    width: Dp = 340.dp,
    focusIndex: Int = options.indexOfFirst { it.selected }.coerceAtLeast(0),
) {
    val focus = remember(title) { FocusRequester() }
    val panelFocus = remember(title) { FocusRequester() }
    // Scrolled so the row to focus is composed (a lazy row below the fold cannot take focus).
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (focusIndex - 2).coerceAtLeast(0))
    LaunchedEffect(title) {
        // Never leave focus behind the scrim: fall back to the panel's first row.
        if (!focus.tryRequestFocus()) panelFocus.tryRequestFocus()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(width)
                .background(AntresolColors.BackgroundElevated)
                .padding(horizontal = 20.dp, vertical = 36.dp)
                .focusRequester(panelFocus)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = Antresol.typography.sectionTitle, modifier = Modifier.padding(horizontal = 12.dp))
            if (note != null) {
                Text(note, style = Antresol.typography.caption, modifier = Modifier.padding(horizontal = 12.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(options) { index, option ->
                    MenuRow(
                        option = option,
                        onClick = {
                            option.onSelect()
                            onDismiss()
                        },
                        modifier = if (index == focusIndex) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuRow(option: MenuOption, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.control),
        colors = listRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(20.dp)) {
                if (option.selected) Icon(AntresolIcons.Check, contentDescription = null, tint = LocalContentColor.current)
            }
            Text(option.label, style = Antresol.typography.bodyEmphasis, color = LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Colours of focusable list rows: transparent at rest, white with black text when focused. */
@Composable
fun listRowColors() = ClickableSurfaceDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = AntresolColors.Label,
    focusedContainerColor = AntresolColors.Label,
    focusedContentColor = AntresolColors.LabelInverse,
    pressedContainerColor = AntresolColors.LabelSecondary,
    pressedContentColor = AntresolColors.LabelInverse,
)

/**
 * A round transport button. [primary] is the big white Play/Pause; [active] marks a toggled
 * mode (shuffle, repeat) with a white dot underneath.
 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    primary: Boolean = false,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(size),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (primary) AntresolColors.Label.copy(alpha = 0.9f) else AntresolColors.Glass,
                contentColor = if (primary) AntresolColors.LabelInverse else AntresolColors.Label,
                focusedContainerColor = AntresolColors.Label,
                focusedContentColor = AntresolColors.LabelInverse,
                pressedContainerColor = AntresolColors.LabelSecondary,
                pressedContentColor = AntresolColors.LabelInverse,
                disabledContainerColor = AntresolColors.FillTertiary,
                disabledContentColor = AntresolColors.LabelTertiary,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = AntresolMotion.FOCUS_SCALE),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.35f), 12.dp)),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = LocalContentColor.current,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(size * 0.44f),
            )
        }
        Box(
            Modifier
                .size(4.dp)
                .background(if (active) AntresolColors.Label else Color.Transparent, CircleShape),
        )
    }
}

/**
 * A text link in the page header (the web app's blue "All Albums" links): accent text at rest,
 * a white pill with black text when focused.
 */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = AntresolColors.Accent,
            focusedContainerColor = AntresolColors.Label,
            focusedContentColor = AntresolColors.LabelInverse,
            pressedContainerColor = AntresolColors.LabelSecondary,
            pressedContentColor = AntresolColors.LabelInverse,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(
            text = text,
            style = Antresol.typography.bodyEmphasis,
            color = LocalContentColor.current,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 14.dp),
        )
    }
}

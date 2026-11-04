package app.antresol.tv.ui.shell

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.LogoMark
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * The web app's top bar: brand on the left, the centred glass pill of tabs (white pill = current
 * tab), and the profile avatar on the right. Entering the pill from below lands on the current tab.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopNav(
    selected: NavTab?,
    userName: String,
    avatar: String,
    onSelect: (NavTab) -> Unit,
    onAccount: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val requesters = remember { NavTab.entries.associateWith { FocusRequester() } }
    var pillFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPaddingH, vertical = 18.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) },
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogoMark(size = 22.dp)
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
                .focusProperties { enter = { requesters.getValue(selected ?: NavTab.Home) } }
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NavTab.entries.forEach { tab ->
                NavPill(
                    tab = tab,
                    selected = tab == selected,
                    rowFocused = pillFocused,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.focusRequester(requesters.getValue(tab)),
                )
            }
        }
        Surface(
            onClick = onAccount,
            modifier = Modifier
                .align(Alignment.CenterEnd)
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
}

@Composable
private fun NavPill(
    tab: NavTab,
    selected: Boolean,
    rowFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Outside the pill the current tab is white; while the pill has focus the focused tab is white
    // and the current one keeps a grey fill so the user still sees where they are.
    val restContainer = when {
        selected && !rowFocused -> AntresolColors.Label
        selected -> AntresolColors.FillPrimary
        else -> Color.Transparent
    }
    val restContent = when {
        selected && !rowFocused -> AntresolColors.LabelInverse
        selected -> AntresolColors.Label
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

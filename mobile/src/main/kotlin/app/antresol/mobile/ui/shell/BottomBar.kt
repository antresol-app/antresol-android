package app.antresol.mobile.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/** Height of the floating bar itself (without the system navigation bar). */
val BottomBarHeight = 64.dp

/** Vertical space the bar takes from the content: the bar plus its bottom margin. */
val BottomBarSpace = BottomBarHeight + 24.dp

/**
 * The floating glass tab bar of the phone design: a pill with Home, Movies, TV Shows, Music, Books
 * and a separate round Search button. The selected tab gets a lighter disc and the accent colour.
 */
@Composable
fun BottomBar(selected: NavTab?, onSelect: (NavTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp)
            .padding(bottom = 10.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .height(BottomBarHeight),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .glass(AntresolShapes.pill)
                .padding(4.dp),
        ) {
            NavTab.entries.filter { it != NavTab.Search }.forEach { tab ->
                TabItem(tab, tab == selected, { onSelect(tab) }, Modifier.weight(1f))
            }
        }
        val searchSelected = selected == NavTab.Search
        Box(
            modifier = Modifier
                .size(BottomBarHeight)
                .glass(CircleShape)
                .clickable(role = Role.Tab) { onSelect(NavTab.Search) }
                .semantics { this.selected = searchSelected }
                .testTag("tab-${NavTab.Search.name}"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = NavTab.Search.icon,
                contentDescription = stringResource(NavTab.Search.label),
                tint = if (searchSelected) AntresolColors.Accent else AntresolColors.Label,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun TabItem(tab: NavTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tint by animateColorAsState(if (selected) AntresolColors.Accent else AntresolColors.LabelSecondary, label = "tab-tint")
    val disc by animateColorAsState(if (selected) AntresolColors.FillSecondary else Color.Transparent, label = "tab-disc")
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(AntresolShapes.pill)
            .background(disc)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("tab-${tab.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            text = stringResource(tab.label),
            style = Antresol.typography.badge.copy(color = tint, fontSize = 10.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape): Modifier = this
    .shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)
    .background(AntresolColors.Glass)
    .border(1.dp, AntresolColors.GlassBorder, shape)

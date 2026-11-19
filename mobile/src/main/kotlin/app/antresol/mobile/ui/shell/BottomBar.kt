package app.antresol.mobile.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/** Height of the floating bar itself (without the system navigation bar). */
val BottomBarHeight = 64.dp

/** Narrowest a tab gets, so a short label («ТВ») still has a usable target and selection disc. */
private val TabMinWidth = 36.dp

/** Tab labels: 10 sp Medium without tracking, so six labels fit a 360 dp phone in full. */
private val TabLabelStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)

/** Vertical space the bar takes from the content: the bar plus its bottom margin. */
val BottomBarSpace = BottomBarHeight + 24.dp

/**
 * The floating glass tab bar of the phone design: a pill with Home, Movies, Shows, ТВ (live
 * channels), Music, Books and a separate round Search button. Six tabs fit a 360 dp phone with the
 * 10 sp labels (as on the web and the iPhone app, where «ТВ» is a tab too). The selected tab gets a lighter disc and the accent colour.
 */
@Composable
fun BottomBar(selected: NavTab?, onSelect: (NavTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp)
            .padding(bottom = 10.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .height(BottomBarHeight),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tabs = NavTab.entries.filter { it != NavTab.Search }
        val labels = tabs.map { stringResource(it.label) }
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Each tab is as wide as its label (at least TabMinWidth) plus an equal share of what is
        // left, so the gaps between labels are even and no label is cut, in any language.
        val labelWidths = remember(labels, density) {
            labels.map { with(density) { measurer.measure(it, TabLabelStyle, maxLines = 1, softWrap = false).size.width.toDp() } }
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .glass(AntresolShapes.pill)
                .padding(2.dp),
        ) {
            val bases = labelWidths.map { maxOf(it, TabMinWidth) }
            val extra = ((maxWidth - bases.fold(0.dp) { a, b -> a + b }) / tabs.size).coerceAtLeast(0.dp)
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, tab ->
                    TabItem(tab, labels[index], tab == selected, { onSelect(tab) }, Modifier.width(bases[index] + extra))
                }
            }
        }
        val searchSelected = selected == NavTab.Search
        Box(
            modifier = Modifier
                .size(BottomBarHeight)
                .glass(CircleShape)
                .focusClickable(CircleShape, role = Role.Tab) { onSelect(NavTab.Search) }
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
private fun TabItem(tab: NavTab, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tint by animateColorAsState(if (selected) AntresolColors.Accent else AntresolColors.LabelSecondary, label = "tab-tint")
    val disc by animateColorAsState(if (selected) AntresolColors.FillSecondary else Color.Transparent, label = "tab-disc")
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(AntresolShapes.pill)
            .background(disc)
            .focusClickable(AntresolShapes.pill, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("tab-${tab.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            style = TabLabelStyle.copy(color = tint),
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape): Modifier = this
    .shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)
    // Android has no backdrop blur for this: a near-opaque fill keeps labels readable over artwork.
    .background(AntresolColors.BackgroundElevated.copy(alpha = 0.97f))
    .border(1.dp, AntresolColors.GlassBorder, shape)

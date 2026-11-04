package app.antresol.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * The segmented sort control of grid pages ("Recently Added | By Title | By Year"). With
 * [selectOnFocus] a pill is selected as soon as it gets focus (season pills), not only on OK.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SegmentedPills(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectOnFocus: Boolean = false,
) {
    var rowFocused by remember { mutableStateOf(false) }
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    Row(
        modifier = modifier
            // Entering the control lands on the selected pill, wherever focus comes from.
            .focusProperties { enter = { requesters.getOrNull(selectedIndex) ?: FocusRequester.Default } }
            .focusGroup()
            .background(AntresolColors.Glass, AntresolShapes.pill)
            .border(1.dp, AntresolColors.GlassBorder, AntresolShapes.pill)
            .padding(4.dp)
            .onFocusChanged { rowFocused = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelect(index) },
                modifier = Modifier
                    .focusRequester(requesters[index])
                    .height(32.dp)
                    .onFocusChanged { if (selectOnFocus && it.isFocused && !selected) onSelect(index) },
                shape = ClickableSurfaceDefaults.shape(AntresolShapes.pill),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = when {
                        selected && !rowFocused -> AntresolColors.Label
                        selected -> AntresolColors.FillPrimary
                        else -> Color.Transparent
                    },
                    contentColor = if (selected && !rowFocused) AntresolColors.LabelInverse else AntresolColors.LabelSecondary,
                    focusedContainerColor = AntresolColors.Label,
                    focusedContentColor = AntresolColors.LabelInverse,
                    pressedContainerColor = AntresolColors.LabelSecondary,
                    pressedContentColor = AntresolColors.LabelInverse,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            ) {
                Box(Modifier.align(Alignment.Center).padding(horizontal = 16.dp)) {
                    Text(
                        text = label,
                        style = Antresol.typography.caption.copy(fontSize = 13.sp),
                        color = LocalContentColor.current,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

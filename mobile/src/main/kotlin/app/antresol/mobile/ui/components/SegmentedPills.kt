package app.antresol.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/** The glass segmented control with a white selected pill ("New | A–Z | By Year"). Scrolls when it does not fit. */
@Composable
fun <T> SegmentedPills(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(AntresolShapes.pill)
            .background(AntresolColors.Glass)
            .border(1.dp, AntresolColors.GlassBorder, AntresolShapes.pill)
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Text(
                text = label(option),
                style = Antresol.typography.bodyEmphasis.copy(
                    color = if (isSelected) AntresolColors.LabelInverse else AntresolColors.LabelSecondary,
                ),
                maxLines = 1,
                modifier = Modifier
                    .clip(AntresolShapes.pill)
                    .background(if (isSelected) AntresolColors.Label else AntresolColors.Glass.copy(alpha = 0f))
                    .semantics { this.selected = isSelected }
                    .focusClickable(AntresolShapes.pill, role = Role.Tab) { onSelect(option) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

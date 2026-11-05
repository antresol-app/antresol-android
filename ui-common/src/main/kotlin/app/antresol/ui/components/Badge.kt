package app.antresol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.antresol.ui.format.Badges
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * A small dark outlined chip with grey text, such as "1080p" or "Audio EN RU" (server-provided card
 * badges). Every kind looks the same, as in the design: nothing under a card competes with the focus ring.
 */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Antresol.typography.badge.copy(color = AntresolColors.LabelSecondary),
        maxLines = 1,
        modifier = modifier
            .background(AntresolColors.FillTertiary, AntresolShapes.badge)
            .border(1.dp, AntresolColors.Hairline, AntresolShapes.badge)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** [Badge]s, quality badges first; they wrap onto a second line in narrow cards (as in the web app) instead of being cut. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BadgeRow(badges: List<String>, modifier: Modifier = Modifier, max: Int = 3) {
    if (badges.isEmpty()) return
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Badges.ordered(badges).take(max).forEach { Badge(it) }
    }
}

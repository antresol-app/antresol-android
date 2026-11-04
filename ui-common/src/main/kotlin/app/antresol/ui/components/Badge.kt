package app.antresol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.antresol.ui.format.BadgeKind
import app.antresol.ui.format.Badges
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/** A small outlined label such as "1080p" or "Audio EN RU" (server-provided card badges). */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    val kind = Badges.kindOf(text)
    val (fill, color) = when (kind) {
        BadgeKind.Quality -> AntresolColors.Label.copy(alpha = 0.92f) to AntresolColors.LabelInverse
        BadgeKind.Info -> AntresolColors.FillTertiary to AntresolColors.LabelSecondary
    }
    Text(
        text = text,
        style = Antresol.typography.badge.copy(color = color),
        maxLines = 1,
        modifier = modifier
            .background(fill, AntresolShapes.badge)
            .border(1.dp, AntresolColors.Hairline, AntresolShapes.badge)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A row of [Badge]s, quality badges first. */
@Composable
fun BadgeRow(badges: List<String>, modifier: Modifier = Modifier, max: Int = 3) {
    if (badges.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Badges.ordered(badges).take(max).forEach { Badge(it) }
    }
}

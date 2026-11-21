package app.antresol.ui.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.antresol.ui.theme.AntresolColors

/**
 * The ring around the button of a way that keeps a copy on the server while it works: filled to
 * [fraction] (0..1), or turning when the server does not say how far it is.
 */
@Composable
fun WayRing(fraction: Float?, modifier: Modifier = Modifier, color: Color = AntresolColors.Label) {
    if (fraction == null) {
        CircularProgressIndicator(color = color, trackColor = Color.Transparent, strokeWidth = 3.dp, modifier = modifier)
    } else {
        CircularProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            color = color,
            trackColor = AntresolColors.FillPrimary,
            strokeWidth = 3.dp,
            modifier = modifier,
        )
    }
}

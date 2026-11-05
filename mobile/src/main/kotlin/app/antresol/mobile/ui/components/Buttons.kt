package app.antresol.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/** The white "Play" pill with black text. */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) = PillButton(text, onClick, modifier, icon, enabled, AntresolColors.Label, AntresolColors.LabelInverse, null)

/** The dark glass pill ("Details", "Want to Watch"). */
@Composable
fun GlassPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) = PillButton(text, onClick, modifier, icon, enabled, AntresolColors.Glass, AntresolColors.Label, AntresolColors.GlassBorder)

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    fill: Color,
    content: Color,
    border: Color?,
) {
    Row(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(AntresolShapes.pill)
            .background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, AntresolShapes.pill) else Modifier)
            .focusClickable(AntresolShapes.pill, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            // 14 dp keeps "Перемешать" whole in a half-width pill on a 360 dp phone.
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        Text(text, style = Antresol.typography.button.copy(color = content), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round dark glass button holding one icon (back, close). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AntresolColors.Label,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(AntresolColors.Glass)
            .border(1.dp, AntresolColors.GlassBorder, CircleShape)
            .focusClickable(CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

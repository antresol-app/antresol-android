package app.antresol.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/** Visual styles of [PillButton]: the white "Play" pill and the dark glass pill. */
enum class PillStyle { Primary, Glass }

/**
 * A pill button as on the web hero: white with black text ([PillStyle.Primary]) or dark glass
 * ([PillStyle.Glass]) that turns white when focused. Focus scales it by 1.07 with a soft glow.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: PillStyle = PillStyle.Glass,
    enabled: Boolean = true,
) {
    val container = when (style) {
        PillStyle.Primary -> AntresolColors.Label.copy(alpha = 0.9f)
        PillStyle.Glass -> AntresolColors.Glass
    }
    val content = when (style) {
        PillStyle.Primary -> AntresolColors.LabelInverse
        PillStyle.Glass -> AntresolColors.Label
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = AntresolColors.Label,
            focusedContentColor = AntresolColors.LabelInverse,
            pressedContainerColor = AntresolColors.LabelSecondary,
            pressedContentColor = AntresolColors.LabelInverse,
            disabledContainerColor = AntresolColors.FillTertiary,
            disabledContentColor = AntresolColors.LabelTertiary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = AntresolMotion.FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.35f), elevation = 12.dp),
        ),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = if (icon != null) 20.dp else 26.dp, end = 26.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = LocalContentColor.current)
            }
            Text(text, style = Antresol.typography.button, color = LocalContentColor.current, maxLines = 1)
        }
    }
}

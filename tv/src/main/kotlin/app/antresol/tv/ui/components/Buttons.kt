package app.antresol.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
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
    /** Hold OK: what else the button does (the choice of a way by hand). */
    onLongClick: (() -> Unit)? = null,
    /** Drawn in place of [icon] (a ring around it, say); 20 dp wide. */
    leading: (@Composable () -> Unit)? = null,
    /** What a screen reader says for a button of an [icon] alone (an empty [text]): the pill is then round. */
    contentDescription: String? = null,
) {
    val iconOnly = text.isEmpty() && icon != null
    Surface(
        onClick = onClick,
        onLongClick = onLongClick?.let { longClick ->
            {
                // The held OK goes on while what it opened takes focus: its release must not click that.
                HeldKeyGuard.arm { false }
                longClick()
            }
        },
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.pill),
        colors = pillColors(style),
        scale = ClickableSurfaceDefaults.scale(focusedScale = AntresolMotion.FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.35f), elevation = 12.dp),
        ),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(
                    start = if (iconOnly) 14.dp else if (icon != null || leading != null) 20.dp else 26.dp,
                    end = if (iconOnly) 14.dp else 26.dp,
                    top = 11.dp,
                    bottom = 11.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leading != null) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { leading() }
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = LocalContentColor.current)
            }
            if (!iconOnly) Text(text, style = Antresol.typography.button, color = LocalContentColor.current, maxLines = 1)
        }
    }
}

/** The colours of a pill: [PillStyle.Primary] white with black text, [PillStyle.Glass] dark, and both white with black text when focused. */
@Composable
private fun pillColors(style: PillStyle) = ClickableSurfaceDefaults.colors(
    containerColor = when (style) {
        PillStyle.Primary -> AntresolColors.Label.copy(alpha = 0.9f)
        PillStyle.Glass -> AntresolColors.Glass
    },
    contentColor = when (style) {
        PillStyle.Primary -> AntresolColors.LabelInverse
        PillStyle.Glass -> AntresolColors.Label
    },
    focusedContainerColor = AntresolColors.Label,
    focusedContentColor = AntresolColors.LabelInverse,
    pressedContainerColor = AntresolColors.LabelSecondary,
    pressedContentColor = AntresolColors.LabelInverse,
    disabledContainerColor = AntresolColors.FillTertiary,
    disabledContentColor = AntresolColors.LabelTertiary,
)

/** Height of a [ChoiceRow]. */
val CHOICE_ROW_HEIGHT = 38.dp

/** The icon or picture at the start of a [ChoiceRow]: one width for all, so the texts of the rows line up. */
val CHOICE_ROW_LEADING = 26.dp

/**
 * One choice of a short list that fills the width it is given (the start prompt): a pill with its icon or picture
 * ([leading], [CHOICE_ROW_LEADING] wide) and its [text] at the start, in the colours of [style]. Focus scales it by 1.05 from
 * its centre and turns it white; at rest the rows of one list all stand on the same left edge and are as wide as each other.
 */
@Composable
fun ChoiceRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Glass,
    leading: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(CHOICE_ROW_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.pill),
        colors = pillColors(style),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            border = if (style == PillStyle.Glass) Border(BorderStroke(1.dp, AntresolColors.GlassBorder)) else Border.None,
            focusedBorder = Border.None,
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.35f), elevation = 12.dp),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(CHOICE_ROW_LEADING), contentAlignment = Alignment.Center) { leading() }
            Text(text, style = Antresol.typography.button, color = LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

package app.antresol.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.ui.R
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import java.util.Locale

/**
 * «● LIVE» in the eyebrow style: the red dot of a live broadcast and [text] (the localized «Live»
 * by default) in caps (or as is, without [caps]). [color] tints the text; the dot stays red.
 */
@Composable
fun LiveBadge(
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.tv_live),
    style: TextStyle = Antresol.typography.eyebrow,
    color: Color = AntresolColors.Label,
    dot: Dp = 7.dp,
    caps: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dot),
    ) {
        Box(
            Modifier
                .size(dot)
                .background(AntresolColors.Red, CircleShape),
        )
        Text(if (caps) text.uppercase(Locale.getDefault()) else text, style = style.copy(color = color), maxLines = 1)
    }
}

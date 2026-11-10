package app.antresol.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import app.antresol.ui.theme.AntresolTheme

/** The gradient of a channel's logo: `logo` 0…6 are the avatar gradients grad-1…grad-7 (as on the web and the server's PNG). */
fun channelLogoBrush(logo: Int): Brush = AntresolGradients.avatar("grad-${Math.floorMod(logo, LOGO_GRADIENTS) + 1}")

private const val LOGO_GRADIENTS = 7

/**
 * A channel's logo tile: the channel's name in bold white, centred on its gradient. 16:9 unless
 * [modifier] gives it another size (a card without a picture fills its artwork with it). The name
 * scales with the tile's width like the web's `13cqw` (11 % when it fills a card, [filled]).
 */
@Composable
fun ChannelLogo(
    name: String,
    logo: Int,
    modifier: Modifier = Modifier,
    shape: Shape = AntresolShapes.smallCard,
    filled: Boolean = false,
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(channelLogoBrush(logo)),
        contentAlignment = Alignment.Center,
    ) {
        val fontSize = with(LocalDensity.current) { (maxWidth * if (filled) 0.11f else 0.13f).toSp() }
        Text(
            text = name,
            style = Antresol.typography.title.copy(
                fontSize = fontSize,
                lineHeight = fontSize * 1.1f,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 6f),
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = maxWidth * 0.08f),
        )
    }
}

/** The small 16:9 logo in the corner of a channel card and in the player's overlay. */
@Composable
fun ChannelLogoBadge(name: String, logo: Int, width: Dp, modifier: Modifier = Modifier) {
    ChannelLogo(
        name = name,
        logo = logo,
        modifier = modifier
            .width(width)
            .aspectRatio(AntresolMotion.BACKDROP_ASPECT),
    )
}

@Preview
@Composable
private fun ChannelLogoPreview() = AntresolTheme {
    ChannelLogoBadge(name = "Комедии", logo = 1, width = 160.dp)
}

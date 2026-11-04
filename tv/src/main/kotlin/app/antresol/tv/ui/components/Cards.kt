package app.antresol.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.components.BadgeRow
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/** Card geometry on the 960 dp TV canvas. */
object CardSizes {
    val posterWidth: Dp = 124.dp
    val squareWidth: Dp = 148.dp
    val wideWidth: Dp = 252.dp
}

/** How a card is drawn in a shelf, by [Card.kind]. */
enum class CardShape { Poster, Square, Round, Wide }

fun Card.defaultShape(): CardShape = when (kind) {
    Kind.ALBUM, Kind.PLAYLIST -> CardShape.Square
    Kind.ARTIST -> CardShape.Round
    else -> CardShape.Poster
}

/**
 * A focusable artwork card with the caption below it (title, subtitle or year, badges), like the
 * web shelves. Focus lifts it by 1.04 with a white edge and a soft glow. [surfaceModifier] goes on
 * the focusable artwork (use it for focus requesters).
 */
@Composable
fun MediaCard(
    card: Card,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    shape: CardShape = card.defaultShape(),
    showBadges: Boolean = true,
) {
    val urls = LocalMediaUrls.current
    val width = when (shape) {
        CardShape.Poster -> CardSizes.posterWidth
        CardShape.Square, CardShape.Round -> CardSizes.squareWidth
        CardShape.Wide -> CardSizes.wideWidth
    }
    val (aspect, outline: Shape) = when (shape) {
        CardShape.Poster -> AntresolMotion.POSTER_ASPECT to AntresolShapes.card
        CardShape.Square -> 1f to AntresolShapes.card
        CardShape.Round -> 1f to CircleShape
        CardShape.Wide -> AntresolMotion.BACKDROP_ASPECT to AntresolShapes.card
    }
    val image = when (shape) {
        CardShape.Wide -> urls?.image(card.backdrop.ifEmpty { card.poster })
        else -> urls?.image(card.poster)
    }
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardSurface(
            onClick = onClick,
            outline = outline,
            modifier = surfaceModifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .onFocusChanged { focused = it.hasFocus },
        ) {
            ArtworkImage(url = image, title = card.title, shape = outline, modifier = Modifier.fillMaxSize()) {
                if (card.progress > 0.0) {
                    ProgressBar(
                        fraction = card.progress.toFloat(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    )
                }
            }
        }
        CardCaption(card, focused, showBadges, centered = shape == CardShape.Round)
    }
}

/** The focusable artwork frame of every card: 1.04 lift, white edge and soft glow on focus. */
@Composable
fun CardSurface(
    onClick: () -> Unit,
    outline: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(outline),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AntresolColors.BackgroundElevated,
            focusedContainerColor = AntresolColors.BackgroundElevated,
            pressedContainerColor = AntresolColors.BackgroundElevated,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = AntresolMotion.CARD_FOCUS_SCALE),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, AntresolColors.Hairline), shape = outline),
            focusedBorder = Border(BorderStroke(2.5.dp, AntresolColors.Label), inset = 0.dp, shape = outline),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.25f), elevation = 16.dp),
        ),
        content = content,
    )
}

@Composable
private fun CardCaption(card: Card, focused: Boolean, showBadges: Boolean, centered: Boolean) {
    val typography = Antresol.typography
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = card.title,
            style = typography.title,
            color = if (focused) AntresolColors.Label else AntresolColors.Label.copy(alpha = 0.88f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val secondary = card.subtitle.ifBlank { card.year?.toString().orEmpty() }
        if (secondary.isNotEmpty()) {
            Text(text = secondary, style = typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showBadges && card.badges.isNotEmpty()) {
            Box(Modifier.padding(top = 4.dp)) { BadgeRow(card.badges, max = 2) }
        }
    }
}

package app.antresol.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.components.BadgeRow
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/** How a card's artwork is framed: 2:3 posters, square albums, round artists, 16:9 continue-watching tiles. */
enum class CardStyle { Poster, Square, Circle, Wide }

/** The natural frame of a card kind outside special shelves. */
fun Card.defaultStyle(): CardStyle = when (kind) {
    Kind.ALBUM, Kind.PLAYLIST -> CardStyle.Square
    Kind.ARTIST -> CardStyle.Circle
    else -> CardStyle.Poster
}

/** Card widths for horizontal shelves, a little larger on tablets. */
object CardSizes {
    @Composable
    fun width(style: CardStyle): Dp {
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        return when (style) {
            CardStyle.Poster, CardStyle.Square -> if (wide) 168.dp else 140.dp
            CardStyle.Circle -> if (wide) 150.dp else 124.dp
            CardStyle.Wide -> if (wide) 320.dp else 272.dp
        }
    }

    /** Minimum column width for adaptive grids: three posters across a phone, more on tablets. */
    @Composable
    fun gridMinWidth(style: CardStyle): Dp {
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        return when (style) {
            CardStyle.Wide -> if (wide) 280.dp else 240.dp
            // 100 dp: three columns fit a 360 dp phone between the 16 dp gutters and 12 dp gaps.
            else -> if (wide) 140.dp else 100.dp
        }
    }
}

/**
 * A library card: artwork (with the title fallback and a progress bar), then title, a caption
 * (subtitle or year) and the server's badges. Pass [width] in rows; grids fill their cell.
 */
@Composable
fun MediaCard(
    card: Card,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CardStyle = card.defaultStyle(),
    width: Dp? = null,
    showBadges: Boolean = true,
    /** A long press (e.g. open the page of a Continue Watching tile that plays on tap). */
    onLongClick: (() -> Unit)? = null,
) {
    val urls = LocalMediaUrls.current
    val sized = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    val circle = style == CardStyle.Circle
    Column(
        modifier = sized
            .clip(AntresolShapes.smallCard)
            .focusClickable(AntresolShapes.smallCard, role = null, onLongClick = onLongClick, onClick = onClick),
        horizontalAlignment = if (circle) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val (image, aspect) = when (style) {
            CardStyle.Poster -> card.poster to AntresolMotion.POSTER_ASPECT
            CardStyle.Square, CardStyle.Circle -> card.poster to 1f
            CardStyle.Wide -> card.backdrop.ifBlank { card.poster } to AntresolMotion.BACKDROP_ASPECT
        }
        ArtworkImage(
            url = urls?.image(image),
            title = card.title,
            shape = if (circle) CircleShape else AntresolShapes.card,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect),
        ) {
            if (card.progress > 0.0) {
                ProgressBar(
                    fraction = card.progress.toFloat(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp),
                )
            }
        }
        Text(
            text = card.title,
            style = Antresol.typography.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (circle) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.padding(top = 6.dp),
        )
        val caption = card.subtitle.ifBlank { card.year?.toString().orEmpty() }
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                style = Antresol.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showBadges && card.badges.isNotEmpty() && !circle) {
            BadgeRow(card.badges, max = 2, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

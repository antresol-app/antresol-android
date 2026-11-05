package app.antresol.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.tv.R as TvR
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/** Horizontal TV safe area: content starts 48 dp from the screen edge. */
val ScreenPaddingH = 48.dp

/** Stable focus key of a card inside a shelf. */
fun cardFocusKey(shelfId: String, card: Card): String = "$shelfId/${card.kind}/${card.id}"

/**
 * A titled horizontal row of cards. D-pad up/down into the row returns to the card that was focused
 * last in it; [focus] remembers the focused card across navigation.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ShelfRow(
    shelfId: String,
    title: String,
    cards: List<Card>,
    onOpen: (Card) -> Unit,
    focus: FocusMemory,
    modifier: Modifier = Modifier,
    shapeOf: (Card) -> CardShape = { it.defaultShape() },
    onLongPress: ((Card) -> Unit)? = null,
    /**
     * Opens the full list behind this shelf: the title gets the design's chevron and the row ends with
     * a "See All" tile. The tile, not the title, takes focus, so Up/Down between shelves stays one press.
     */
    onSeeAll: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title.isNotBlank()) {
            Row(
                modifier = Modifier.padding(horizontal = ScreenPaddingH),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = title, style = Antresol.typography.sectionTitle)
                if (onSeeAll != null) {
                    Icon(AntresolIcons.ChevronRight, contentDescription = null, tint = AntresolColors.LabelSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
        HorizontalRowScrolling {
            LazyRow(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = ScreenPaddingH, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .trapHorizontalFocus()
                    .focusRestorer(),
            ) {
                items(cards, key = { "${it.kind}/${it.id}" }) { card ->
                    MediaCard(
                        card = card,
                        onClick = { onOpen(card) },
                        shape = shapeOf(card),
                        surfaceModifier = Modifier.remembersFocus(focus, cardFocusKey(shelfId, card)),
                        onLongClick = onLongPress?.let { { it(card) } },
                    )
                }
                if (onSeeAll != null && cards.isNotEmpty()) {
                    item(key = "see-all") {
                        SeeAllTile(
                            shape = shapeOf(cards.last()),
                            onClick = onSeeAll,
                            modifier = Modifier.remembersFocus(focus, "$shelfId/see-all"),
                        )
                    }
                }
            }
        }
    }
}

/** The last tile of a shelf with a full list: a chevron in the shelf's card shape, "See All" below. */
@Composable
private fun SeeAllTile(shape: CardShape, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val width = when (shape) {
        CardShape.Poster -> CardSizes.posterWidth
        CardShape.Square, CardShape.Round -> CardSizes.squareWidth
        CardShape.Wide -> CardSizes.wideWidth
    }
    val aspect = when (shape) {
        CardShape.Poster -> AntresolMotion.POSTER_ASPECT
        CardShape.Square, CardShape.Round -> 1f
        CardShape.Wide -> AntresolMotion.BACKDROP_ASPECT
    }
    val outline = if (shape == CardShape.Round) CircleShape else AntresolShapes.card
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardSurface(
            onClick = onClick,
            outline = outline,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(aspect),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(AntresolColors.FillTertiary, outline),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(AntresolColors.Glass, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AntresolIcons.ChevronRight, contentDescription = null, tint = AntresolColors.Label, modifier = Modifier.size(22.dp))
                }
            }
        }
        Text(
            stringResource(TvR.string.shelf_see_all),
            style = Antresol.typography.title,
            maxLines = 1,
            textAlign = if (shape == CardShape.Round) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

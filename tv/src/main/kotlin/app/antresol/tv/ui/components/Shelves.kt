package app.antresol.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.ui.theme.Antresol

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
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = Antresol.typography.sectionTitle,
                modifier = Modifier.padding(horizontal = ScreenPaddingH),
            )
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
            }
        }
    }
}

package app.antresol.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.antresol.core.model.Card
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/** Horizontal margin of page content. */
val PageGutter = 16.dp

/** A bold section title with an optional chevron that opens the full list. */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .then(if (onSeeAll != null) Modifier.clickable(onClick = onSeeAll) else Modifier)
            .padding(horizontal = PageGutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = Antresol.typography.sectionTitle)
        if (onSeeAll != null) {
            Icon(
                imageVector = AntresolIcons.ChevronRight,
                contentDescription = null,
                tint = AntresolColors.LabelSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A titled horizontal row of cards. */
@Composable
fun Shelf(
    title: String,
    cards: List<Card>,
    onOpen: (Card) -> Unit,
    modifier: Modifier = Modifier,
    style: CardStyle? = null,
    onSeeAll: (() -> Unit)? = null,
    onLongPress: ((Card) -> Unit)? = null,
) {
    Column(modifier = modifier.padding(top = 20.dp)) {
        SectionTitle(title, onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PageGutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            items(cards, key = { "${it.kind}:${it.id}" }) { card ->
                val cardStyle = style ?: card.defaultStyle()
                MediaCard(
                    card = card,
                    onClick = { onOpen(card) },
                    style = cardStyle,
                    width = CardSizes.width(cardStyle),
                    onLongClick = onLongPress?.let { { it(card) } },
                )
            }
        }
    }
}

/** A row of accent text links ("All Albums", "Artists", …) under a page title. */
@Composable
fun LinkRow(links: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = PageGutter - 4.dp),
    ) {
        items(links, key = { it.first }) { (label, onClick) ->
            Text(
                text = label,
                style = Antresol.typography.bodyEmphasis.copy(color = AntresolColors.Accent),
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .padding(horizontal = 4.dp, vertical = 8.dp)
                    .padding(end = 20.dp),
            )
        }
    }
}

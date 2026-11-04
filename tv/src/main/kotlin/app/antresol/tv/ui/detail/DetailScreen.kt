package app.antresol.tv.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.ShelfRow
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.cardFocusKey
import app.antresol.tv.ui.components.kindLabel
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.home.HeroBackground
import app.antresol.ui.R
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.components.BadgeRow
import app.antresol.ui.format.spokenDuration
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion

/**
 * The detail page of any card: tinted backdrop, artwork, title, meta and overview, then related
 * rows (seasons with episodes, an artist's albums, comic volumes) that open further details.
 */
@Composable
fun DetailScreen(viewModel: DetailViewModel, onOpen: (Card) -> Unit) {
    when (val state = viewModel.state) {
        LoadState.Loading -> LoadingView()
        is LoadState.Failed -> ErrorView(state.error, onRetry = viewModel::reload)
        is LoadState.Ready -> DetailContent(state.value, onOpen)
    }
}

@Composable
private fun DetailContent(model: DetailModel, onOpen: (Card) -> Unit) {
    val focus = rememberFocusMemory()
    val headerHeight = (LocalConfiguration.current.screenHeightDp * if (model.rows.isEmpty()) 1f else 0.62f).dp
    LaunchedEffect(model) {
        val (rowId, card) = model.preferredCard ?: model.rows.firstOrNull()?.let { it.id to it.cards.first() } ?: return@LaunchedEffect
        focus.restore(cardFocusKey(rowId, card))
    }
    Box(Modifier.fillMaxSize()) {
        HeroBackground(model.card)
        VerticalShelfScrolling {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = headerHeight)
                            .padding(horizontal = ScreenPaddingH, vertical = 32.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        DetailHeader(model)
                    }
                }
                items(model.rows, key = { it.id }) { row ->
                    ShelfRow(
                        shelfId = row.id,
                        title = row.titleRes?.let { stringResource(it) } ?: row.title,
                        cards = row.cards,
                        onOpen = onOpen,
                        focus = focus,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(model: DetailModel) {
    val typography = Antresol.typography
    val card = model.card
    val urls = LocalMediaUrls.current
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Bottom) {
        if (card.backdrop.isEmpty()) {
            val square = card.kind in setOf(Kind.ALBUM, Kind.ARTIST, Kind.PLAYLIST)
            ArtworkImage(
                url = urls?.image(card.poster),
                title = card.title,
                modifier = Modifier
                    .width(if (square) 190.dp else 160.dp)
                    .aspectRatio(if (square) 1f else AntresolMotion.POSTER_ASPECT),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 600.dp)) {
            kindLabel(card.kind)?.let { Text(it.uppercase(), style = typography.eyebrow) }
            Text(card.title, style = typography.heroTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val byline = listOfNotNull(
                model.byline.ifBlank { null } ?: card.subtitle.ifBlank { null },
                model.narrator.ifBlank { null }?.let { stringResource(R.string.detail_narrated_by, it) },
            ).joinToString(" · ")
            if (byline.isNotEmpty()) Text(byline, style = typography.bodyEmphasis, color = AntresolColors.LabelSecondary)
            val meta = listOfNotNull(
                card.year?.toString(),
                model.durationMs?.let { spokenDuration(it) },
                model.songCount?.let { stringResource(R.string.detail_song_count, it) },
                model.rating?.ifBlank { null },
                model.genres.take(3).joinToString(", ").ifBlank { null },
            ).joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (meta.isNotEmpty()) Text(meta, style = typography.body, color = AntresolColors.LabelSecondary)
                BadgeRow(card.badges, max = 4)
            }
            if (model.overview.isNotBlank()) {
                Text(
                    text = model.overview,
                    style = typography.body,
                    color = AntresolColors.Label.copy(alpha = 0.85f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

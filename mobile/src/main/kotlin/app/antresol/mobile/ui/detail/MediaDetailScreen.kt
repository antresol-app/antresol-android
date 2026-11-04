package app.antresol.mobile.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.antresol.core.model.Card
import app.antresol.core.model.Chapter
import app.antresol.core.model.Kind
import app.antresol.core.model.Track
import app.antresol.mobile.R
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.SectionTitle
import app.antresol.mobile.ui.components.Shelf
import app.antresol.mobile.ui.components.kindLabel
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.format.Durations
import app.antresol.ui.format.spokenDuration
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes
import java.util.Locale
import app.antresol.ui.R as UiR

/**
 * Album, artist, playlist, book, audiobook and comic detail: centred artwork on its own tint,
 * title, byline and facts, the Want to Read toggle for books, description, then songs,
 * chapters, volumes or albums.
 */
@Composable
fun MediaDetailScreen(viewModel: MediaDetailViewModel, onOpen: (Card) -> Unit, onBack: () -> Unit) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    FailureSnackbar(viewModel.failed, snackbar, viewModel::consumeFailure)
    Box(Modifier.fillMaxSize().background(AntresolColors.Background)) {
        when (val state = viewModel.state) {
            LoadState.Loading -> DetailStateBox(onBack) { LoadingView() }
            is LoadState.Failed -> DetailStateBox(onBack) { ErrorView(state.error, onRetry = viewModel::reload) }
            is LoadState.Ready -> MediaDetailContent(state.value, viewModel::toggleWant, onOpen, onBack)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(LocalBottomBarPadding.current))
    }
}

@Composable
private fun MediaDetailContent(detail: MediaDetail, onToggleWant: () -> Unit, onOpen: (Card) -> Unit, onBack: () -> Unit) {
    val urls = LocalMediaUrls.current
    val card = detail.card
    val bottom = LocalBottomBarPadding.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + 24.dp),
    ) {
        item("header") {
            DetailHeader(tintUrl = urls?.image(card.poster), onBack = onBack, artwork = {}) {
                val (aspect, shape) = when (card.kind) {
                    Kind.ALBUM, Kind.PLAYLIST -> 1f to AntresolShapes.card
                    Kind.ARTIST -> 1f to CircleShape
                    else -> AntresolMotion.POSTER_ASPECT to AntresolShapes.card
                }
                ArtworkImage(
                    url = urls?.image(card.poster),
                    title = card.title,
                    shape = shape,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = 240.dp)
                        .fillMaxWidth(if (aspect < 1f) 0.5f else 0.62f)
                        .aspectRatio(aspect),
                )
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    kindLabel(card.kind)?.let { Text(it.uppercase(Locale.getDefault()), style = Antresol.typography.eyebrow) }
                    Text(
                        card.title,
                        style = Antresol.typography.pageTitle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp).testTag("detail-title"),
                    )
                    if (detail.byline.isNotBlank()) {
                        Text(detail.byline, style = Antresol.typography.bodyEmphasis.copy(color = AntresolColors.Accent), textAlign = TextAlign.Center)
                    }
                    if (detail.narrator.isNotBlank()) {
                        Text(stringResource(UiR.string.detail_narrated_by, detail.narrator), style = Antresol.typography.caption, textAlign = TextAlign.Center)
                    }
                    val meta = listOfNotNull(
                        detail.year?.toString(),
                        detail.genres.takeIf { it.isNotEmpty() }?.take(2)?.joinToString(", "),
                        detail.songCount?.takeIf { it > 0 }?.let { stringResource(UiR.string.detail_song_count, it) },
                        detail.durationMs?.let { spokenDuration(it) },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = Antresol.typography.caption, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        detail.want?.let { want ->
            item("want") {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    GlassPillButton(
                        text = stringResource(R.string.detail_want),
                        icon = if (want) MobileIcons.Check else MobileIcons.Plus,
                        onClick = onToggleWant,
                        modifier = Modifier.testTag("toggle-want"),
                    )
                }
            }
        }
        if (detail.description.isNotBlank()) item("description") { ExpandableText(detail.description) }
        if (detail.tracks.isNotEmpty()) {
            item("tracks-title") { SectionTitle(stringResource(UiR.string.detail_tracks), Modifier.padding(top = 16.dp)) }
            itemsIndexed(detail.tracks, key = { index, track -> "track-$index-${track.id}" }) { index, track ->
                TrackRow(track, number = if (detail.tracksShowArtist) index + 1 else track.trackNo.takeIf { it > 0 } ?: (index + 1), showArtist = detail.tracksShowArtist)
            }
        }
        if (detail.chapters.isNotEmpty()) {
            item("chapters-title") { SectionTitle(stringResource(UiR.string.detail_chapters), Modifier.padding(top = 16.dp)) }
            items(detail.chapters, key = { "chapter-${it.idx}" }) { ChapterRow(it) }
        }
        items(detail.rows, key = { it.id }) { row ->
            Shelf(title = stringResource(row.titleRes), cards = row.cards, onOpen = onOpen)
        }
    }
}

@Composable
private fun TrackRow(track: Track, number: Int, showArtist: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(number.toString(), style = Antresol.typography.caption, textAlign = TextAlign.End, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = Antresol.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showArtist && track.artist.isNotBlank()) {
                Text(track.artist, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(Durations.clock(track.durationMs), style = Antresol.typography.caption)
    }
}

@Composable
private fun ChapterRow(chapter: Chapter) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(chapter.title, style = Antresol.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(Durations.clock(chapter.startMs), style = Antresol.typography.caption)
    }
}

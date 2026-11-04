package app.antresol.tv.ui.detail

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.AlbumDetail
import app.antresol.core.model.ArtistDetail
import app.antresol.core.model.BookDetail
import app.antresol.core.model.Card
import app.antresol.core.model.ItemDetail
import app.antresol.core.model.Kind
import app.antresol.core.model.PlaylistDetail
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.load
import app.antresol.ui.R
import kotlinx.coroutines.launch

/** A row of related cards on a detail page; the title is server text or a string resource. */
data class DetailRow(
    val id: String,
    val title: String = "",
    @StringRes val titleRes: Int? = null,
    val cards: List<Card>,
)

/** What the detail page shows, unified over items, albums, artists, playlists and books. */
data class DetailModel(
    val card: Card,
    val byline: String = "",
    val narrator: String = "",
    val durationMs: Long? = null,
    val rating: String? = null,
    val genres: List<String> = emptyList(),
    val songCount: Int? = null,
    val overview: String = "",
    val rows: List<DetailRow> = emptyList(),
    /** Focus key of the card to focus first (the next episode of a series). */
    val preferredCard: Pair<String, Card>? = null,
)

/** Loads the detail of any card kind through the matching endpoint. */
class DetailViewModel(
    private val client: AntresolClient,
    private val kind: String,
    private val id: Long,
) : ViewModel() {
    var state: LoadState<DetailModel> by mutableStateOf(LoadState.Loading)
        private set

    init {
        reload()
    }

    fun reload() {
        state = LoadState.Loading
        viewModelScope.launch { state = load { fetch() } }
    }

    private suspend fun fetch(): DetailModel = when (kind) {
        Kind.ALBUM -> client.album(id).toModel()
        Kind.ARTIST -> client.artist(id).toModel()
        Kind.PLAYLIST -> client.playlist(id).toModel()
        Kind.AUDIOBOOK, Kind.BOOK, Kind.COMIC, Kind.COMIC_VOLUME -> client.book(id).toModel()
        else -> client.item(id).toModel()
    }
}

private fun ItemDetail.toModel(): DetailModel {
    val rows = seasons.filter { it.episodes.isNotEmpty() }.map { season ->
        DetailRow(id = "season-${season.id}", title = season.title, cards = season.episodes.map { it.toCard() })
    }
    val nextCard = next?.toCard()
    val preferred = nextCard?.let { card -> rows.firstOrNull { row -> row.cards.any { it.id == card.id } }?.let { it.id to card } }
    val fileDuration = files.firstOrNull()?.durationMs
    return DetailModel(
        card = toCard(),
        byline = originalTitle?.takeIf { it.isNotBlank() && it != title }.orEmpty(),
        durationMs = runtime?.let { it * 60_000L } ?: fileDuration,
        rating = rating,
        genres = genres,
        overview = overview,
        rows = rows,
        preferredCard = preferred,
    )
}

private fun AlbumDetail.toModel() = DetailModel(
    card = card,
    byline = artist?.name.orEmpty(),
    durationMs = durationMs.takeIf { it > 0 },
    genres = genres,
    songCount = trackCount.takeIf { it > 0 } ?: tracks.size,
)

private fun ArtistDetail.toModel() = DetailModel(
    card = card,
    rows = listOf(
        DetailRow(id = "albums", titleRes = R.string.detail_albums, cards = albums),
        DetailRow(id = "appears-on", titleRes = R.string.detail_appears_on, cards = appearsOn),
    ).filter { it.cards.isNotEmpty() },
)

private fun PlaylistDetail.toModel() = DetailModel(
    card = Card(id = playlist.id, kind = Kind.PLAYLIST, title = playlist.name, poster = playlist.cover),
    durationMs = playlist.durationMs.takeIf { it > 0 },
    songCount = playlist.trackCount,
)

private fun BookDetail.toModel() = DetailModel(
    card = card,
    byline = authors.joinToString(", "),
    narrator = narrator,
    durationMs = durationMs.takeIf { it > 0 },
    overview = description,
    rows = listOf(DetailRow(id = "volumes", titleRes = R.string.detail_volumes, cards = volumes.map { it.toCard() }))
        .filter { it.cards.isNotEmpty() },
)

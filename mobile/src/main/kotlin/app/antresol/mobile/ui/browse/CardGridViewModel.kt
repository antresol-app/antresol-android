package app.antresol.mobile.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.antresol.core.api.AntresolClient
import androidx.lifecycle.viewModelScope
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.Page
import app.antresol.mobile.ui.PagedViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.antresol.ui.R as UiR

/** A paged, sortable grid of cards from a [GridSource]. */
class CardGridViewModel(private val client: AntresolClient, val source: GridSource) : PagedViewModel<Card>() {
    var sort: GridSort? by mutableStateOf(source.sorts.firstOrNull())
        private set

    /** A string resource to show once (a playlist could not be created). */
    var notice: Int? by mutableStateOf(null)
        private set

    init {
        reload()
    }

    fun consumeNotice() {
        notice = null
    }

    /** The Playlists grid: creates an empty playlist named [name] and opens it with [onCreated]. */
    fun createPlaylist(name: String, onCreated: (Card) -> Unit) {
        viewModelScope.launch {
            try {
                val playlist = client.createPlaylist(name.trim())
                refresh(pulled = false)
                onCreated(Card(id = playlist.id, kind = Kind.PLAYLIST, title = playlist.name, poster = playlist.cover))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = UiR.string.playlist_failed
            }
        }
    }

    fun select(newSort: GridSort) {
        if (newSort == sort) return
        sort = newSort
        reload()
    }

    override suspend fun fetch(offset: Int): Page<Card> = source.page(client, sort, offset)

    override fun keyOf(item: Card): Any = "${item.kind}:${item.id}"
}

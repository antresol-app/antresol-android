package app.antresol.mobile.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Card
import app.antresol.core.model.Page
import app.antresol.mobile.ui.PagedViewModel

/** A paged, sortable grid of cards from a [GridSource]. */
class CardGridViewModel(private val client: AntresolClient, val source: GridSource) : PagedViewModel<Card>() {
    var sort: GridSort? by mutableStateOf(source.sorts.firstOrNull())
        private set

    init {
        reload()
    }

    fun select(newSort: GridSort) {
        if (newSort == sort) return
        sort = newSort
        reload()
    }

    override suspend fun fetch(offset: Int): Page<Card> = source.page(client, sort, offset)

    override fun keyOf(item: Card): Any = "${item.kind}:${item.id}"
}

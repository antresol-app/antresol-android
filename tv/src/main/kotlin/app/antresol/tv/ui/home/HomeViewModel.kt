package app.antresol.tv.ui.home

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.HomeContent
import app.antresol.core.playback.PendingWrites
import app.antresol.ui.LoadViewModel

/**
 * Loads Home. It refreshes in place whenever the screen comes back (from the player, a detail
 * page) and when a final progress write lands, so Continue Watching reflects what was just played;
 * failures keep the shown content.
 */
class HomeViewModel(client: AntresolClient, pendingWrites: PendingWrites) :
    LoadViewModel<HomeContent>(staleAfterMs = 0, pendingWrites = pendingWrites, fetch = { HomeContent.from(client.home().withoutBooks()) })

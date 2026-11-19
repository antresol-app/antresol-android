package app.antresol.tv.homechannels

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.Progress
import app.antresol.core.model.ShelfId
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager

/**
 * Asks the server what the app's own home screen asks: `GET /api/home` (its «continue» shelf and the recent movies
 * and series), plus `GET /api/progress/{id}` for each started video, which holds the length that the home screen's
 * progress bar needs and the card does not carry. The profile's kid limits and «Отдельная история» are applied by
 * the server, so its lists are used as they come.
 */
class ServerHomeSource(private val session: SessionManager) : HomeSource {
    override fun signedIn(): Boolean = session.state.value is AuthState.SignedIn

    override suspend fun load(): HomeSnapshot? {
        // A run that starts the process (WorkManager) has no screen to restore the session first.
        if (session.state.value == AuthState.Starting) session.restore()
        val state = session.state.value as? AuthState.SignedIn ?: return null
        // Pinned to this profile's token: a profile switch halfway through must not mix two profiles' lists.
        val client = session.client?.pinned() ?: return null

        val shelves = client.home().withoutBooks().shelves.associateBy { it.id }
        val started = shelves[ShelfId.CONTINUE]?.cards.orEmpty()
            .filter { it.kind in Kind.VIDEO && !it.watched }
            .distinctBy { it.id }
            .take(HomeMapper.MAX_WATCH_NEXT)
        val items = started.mapNotNull { card -> continueItem(client, card) }
        return HomeSnapshot(
            serverId = state.session.serverId,
            userId = state.session.userId,
            continueItems = items,
            recentMovies = shelves[ShelfId.RECENT_MOVIES]?.cards.orEmpty(),
            recentSeries = shelves[ShelfId.RECENT_SERIES]?.cards.orEmpty(),
        )
    }

    /** The card with its saved position; null when the title is gone from the library (404). Other failures stop the run. */
    private suspend fun continueItem(client: AntresolClient, card: Card): ContinueItem? {
        // A next-up episode has nothing played yet: no position to ask for.
        if (card.progress <= 0.0) return ContinueItem(card, null)
        val progress: Progress = try {
            client.progress(card.id)
        } catch (e: ApiException) {
            if (e.isNotFound) return null
            throw e
        }
        return ContinueItem(card, progress)
    }
}

package app.antresol.core.requests

import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.KidCandidate
import app.antresol.core.model.KidRequest
import app.antresol.core.model.RequestSearchKind
import app.antresol.core.model.RequestStatus

/**
 * What a kid's search of titles to ask for shows as a card, and the candidate behind it. A movie or series
 * opens the page of its title ([Card.opensTitlePage]); one the library has opens its own page; an album or a
 * book has no page, so its card is made unique by a negative id and a tap asks for it (see [AskCards.candidateOf]).
 */
data class AskCard(val card: Card, val candidate: KidCandidate)

object AskCards {
    /** The cards of [candidates], each with [badge] of its request (null: none). */
    fun of(candidates: List<KidCandidate>, badge: (KidRequest?) -> String?): List<AskCard> =
        candidates.mapIndexed { index, candidate -> AskCard(card(candidate, index, badge(candidate.kidRequest?.request)), candidate) }

    private fun card(c: KidCandidate, index: Int, badge: String?): Card {
        val badges = listOfNotNull(badge)
        val inLibrary = c.kidRequest?.request?.takeIf { it.status == RequestStatus.IN_LIBRARY }?.libraryItemId?.takeIf { it > 0 }
        return when {
            c.isVideo && inLibrary != null ->
                Card(id = inLibrary, kind = c.kind, title = c.title, year = c.year.takeIf { it > 0 }, poster = c.poster, badges = badges)
            c.isVideo ->
                Card(id = 0, kind = c.kind, title = c.title, year = c.year.takeIf { it > 0 }, poster = c.poster, badges = badges, onServer = false, tmdbId = c.tmdbId)
            else ->
                Card(id = -(index + 1L), kind = cardKind(c.kind), title = c.title, subtitle = c.subtitle, year = c.year.takeIf { it > 0 }, badges = badges, onServer = false)
        }
    }

    /** The candidate [card] was made of, or null for a card that is not one of [cards]. */
    fun candidateOf(cards: List<AskCard>, card: Card): KidCandidate? = cards.firstOrNull { it.card == card }?.candidate

    /** The kinds of the kid's request search a search page filter shows: movies and series (video), music, books. */
    fun searchKinds(video: Boolean = false, music: Boolean = false, books: Boolean = false): List<String> = buildList {
        if (video) add(RequestSearchKind.VIDEO)
        if (music) add(RequestSearchKind.MUSIC)
        if (books) {
            add(RequestSearchKind.BOOK)
            add(RequestSearchKind.AUDIOBOOK)
            add(RequestSearchKind.COMIC)
        }
    }

    /** The card kind that draws a request's [kind] (an album is a square card, a book a poster-shaped one). */
    private fun cardKind(kind: String): String = if (kind == RequestSearchKind.MUSIC) Kind.ALBUM else kind
}

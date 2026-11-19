package app.antresol.tv.homechannels

import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.Progress

const val SERVER = "srv"
const val USER = 7L

fun card(
    id: Long,
    kind: String = Kind.MOVIE,
    title: String = "Title $id",
    subtitle: String = "",
    year: Int? = null,
    poster: String = "/api/images/local/$id/poster",
    backdrop: String = "",
    thumb: String = "",
    progress: Double = 0.0,
    watched: Boolean = false,
    seriesId: Long? = null,
    season: Int? = null,
    episode: Int? = null,
) = Card(
    id = id, kind = kind, title = title, subtitle = subtitle, year = year, poster = poster, backdrop = backdrop,
    thumb = thumb, progress = progress, watched = watched, seriesId = seriesId, season = season, episode = episode,
)

fun started(card: Card, positionMs: Long = 600_000, durationMs: Long = 6_000_000, finished: Boolean = false) =
    ContinueItem(card, Progress(positionMs = positionMs, durationMs = durationMs, finished = finished, updatedAt = "2026-11-19T10:00:00Z"))

fun snapshot(
    continueItems: List<ContinueItem> = emptyList(),
    movies: List<Card> = emptyList(),
    series: List<Card> = emptyList(),
    userId: Long = USER,
) = HomeSnapshot(SERVER, userId, continueItems, movies, series)

package app.antresol.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.antresol.core.model.Kind
import app.antresol.ui.R

/** Localized singular name of a card kind ("Movie", "TV Show"), or null for kinds without one. */
@Composable
fun kindLabel(kind: String): String? = when (kind) {
    Kind.MOVIE -> stringResource(R.string.kind_movie)
    Kind.SERIES, Kind.SEASON -> stringResource(R.string.kind_series)
    Kind.EPISODE -> stringResource(R.string.kind_episode)
    Kind.ALBUM -> stringResource(R.string.kind_album)
    Kind.ARTIST -> stringResource(R.string.kind_artist)
    Kind.PLAYLIST -> stringResource(R.string.kind_playlist)
    Kind.AUDIOBOOK -> stringResource(R.string.kind_audiobook)
    Kind.BOOK -> stringResource(R.string.kind_book)
    Kind.COMIC, Kind.COMIC_VOLUME -> stringResource(R.string.kind_comic)
    else -> null
}

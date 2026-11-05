package app.antresol.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.antresol.core.model.EpisodeCard
import app.antresol.core.model.SeasonInfo
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.CardSizes
import app.antresol.tv.ui.components.CardSurface
import app.antresol.tv.ui.components.FocusMemory
import app.antresol.tv.ui.components.HorizontalRowScrolling
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.SegmentedPills
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.tv.R as TvR
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.format.spokenDuration
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/** Focus key of an episode card on a series page. */
fun episodeFocusKey(episode: EpisodeCard) = "episode/${episode.id}"

/**
 * Season pills (or the season title when there is one) above a row of 16:9 episode cards with
 * progress and watched marks. Starts on the season of [initialEpisodeId], scrolled to it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeasonsSection(
    seasons: List<SeasonInfo>,
    initialEpisodeId: Long?,
    focus: FocusMemory,
    onPlayEpisode: (EpisodeCard) -> Unit,
    onOpenEpisode: (EpisodeCard) -> Unit,
) {
    var selected by rememberSaveable {
        mutableIntStateOf(seasons.indexOfFirst { season -> season.episodes.any { it.id == initialEpisodeId } }.coerceAtLeast(0))
    }
    val season = seasons[selected.coerceIn(seasons.indices)]
    val titles = seasons.map { it.title.ifBlank { stringResource(R.string.detail_season_number, it.number) } }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = ScreenPaddingH)) {
            if (seasons.size > 1) {
                SegmentedPills(options = titles, selectedIndex = selected, onSelect = { selected = it }, selectOnFocus = true)
            } else {
                Text(titles.first(), style = Antresol.typography.sectionTitle)
            }
        }
        // Each season gets its own scroll state and focus restorer, starting at the episode to watch.
        key(season.number) {
            SeasonRow(season, initialEpisodeId, focus, onPlayEpisode, onOpenEpisode)
        }
        Text(
            stringResource(TvR.string.detail_episode_hold_hint),
            style = Antresol.typography.caption,
            modifier = Modifier.padding(horizontal = ScreenPaddingH),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeasonRow(
    season: SeasonInfo,
    initialEpisodeId: Long?,
    focus: FocusMemory,
    onPlayEpisode: (EpisodeCard) -> Unit,
    onOpenEpisode: (EpisodeCard) -> Unit,
) {
    val start = season.episodes.indexOfFirst { it.id == initialEpisodeId }
        .takeIf { it >= 0 }
        ?: season.episodes.indexOfFirst { it.progress > 0.0 && !it.watched }.coerceAtLeast(0)
    HorizontalRowScrolling {
        LazyRow(
            state = rememberLazyListState(initialFirstVisibleItemIndex = start),
            contentPadding = PaddingValues(horizontal = ScreenPaddingH, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .trapHorizontalFocus()
                .focusRestorer(),
        ) {
            items(season.episodes, key = { it.id }) { episode ->
                EpisodeTile(
                    episode = episode,
                    onClick = { onPlayEpisode(episode) },
                    onLongClick = { onOpenEpisode(episode) },
                    surfaceModifier = Modifier.remembersFocus(focus, episodeFocusKey(episode)),
                )
            }
        }
    }
}

@Composable
private fun EpisodeTile(episode: EpisodeCard, onClick: () -> Unit, onLongClick: () -> Unit, surfaceModifier: Modifier) {
    val urls = LocalMediaUrls.current
    val outline = AntresolShapes.card
    Column(Modifier.width(CardSizes.wideWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            outline = outline,
            modifier = surfaceModifier
                .fillMaxWidth()
                .aspectRatio(AntresolMotion.BACKDROP_ASPECT),
        ) {
            ArtworkImage(
                url = urls?.image(episode.backdrop.ifEmpty { episode.poster }),
                title = episode.title,
                shape = outline,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (episode.watched) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp)
                            .background(AntresolColors.Glass, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AntresolIcons.Check, contentDescription = stringResource(R.string.detail_watched), tint = AntresolColors.Label, modifier = Modifier.size(16.dp))
                    }
                } else if (episode.progress > 0.0) {
                    ProgressBar(
                        fraction = episode.progress.toFloat(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.detail_episode_title, episode.episodeNo, episode.title),
            style = Antresol.typography.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        episode.durationMs?.takeIf { it > 0 }?.let {
            Text(spokenDuration(it), style = Antresol.typography.caption, maxLines = 1)
        }
        episode.overview?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = Antresol.typography.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

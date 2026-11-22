package app.antresol.tv.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.model.AddonWay
import app.antresol.core.model.EpisodeCard
import app.antresol.core.library.EpisodeGuide
import app.antresol.core.library.EpisodeState
import app.antresol.core.library.GuideEpisode
import app.antresol.core.library.GuideSeason
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
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.components.EpisodeMarks
import app.antresol.ui.components.ProgressBar
import app.antresol.ui.components.WatchedMarkOverlay
import app.antresol.ui.components.WatchedStyle
import app.antresol.ui.format.EpisodeLabels
import app.antresol.ui.format.episodeStateLabel
import app.antresol.ui.format.seasonLabel
import app.antresol.ui.format.spokenDuration
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/** Focus key of an episode card on a series page. */
fun episodeFocusKey(episode: GuideEpisode) = "episode/${episode.key}"

/**
 * Season pills (or the season title when there is one) above a row of 16:9 episode cards with
 * progress and watched marks. Starts on the season of [initialEpisodeId], scrolled to it.
 *
 * With the server's episode guide every episode shows: library ones play (OK) and open (hold OK)
 * as before; one that is not downloaded is dimmed (not when an addon's way plays it) and one not aired
 * yet greyed, with their mark; they take focus (so the row scrolls to them), and with no way they do
 * nothing, and the hint under the row says why.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeasonsSection(
    guide: EpisodeGuide,
    initialEpisodeId: Long?,
    focus: FocusMemory,
    onPlayEpisode: (EpisodeCard) -> Unit,
    onOpenEpisode: (EpisodeCard) -> Unit,
    modifier: Modifier = Modifier,
    /** The ways of the addons for an episode (none: the episode has only what the library gives it). */
    episodeWays: (GuideEpisode) -> List<AddonWay> = { emptyList() },
    /** Plays an episode that is not in the library by the title's way (OK on it): the first of the ways given. */
    onPlayEpisodeWay: (GuideEpisode, List<AddonWay>) -> Unit = { _, _ -> },
    /** Opens the menu of an episode's ways (hold OK on any episode that has some). */
    onEpisodeWays: (GuideEpisode, List<AddonWay>) -> Unit = { _, _ -> },
    /** A run of a way for this very episode is waiting for the addon: its tile says so. */
    episodeBusy: (GuideEpisode) -> Boolean = { false },
) {
    val seasons = guide.seasons
    var selected by rememberSaveable {
        mutableIntStateOf(
            seasons.indexOfFirst { season -> season.libraryEpisodes.any { it.id == initialEpisodeId } }
                .takeIf { it >= 0 }
                ?: seasons.indexOfFirst { it.libraryEpisodes.isNotEmpty() }.coerceAtLeast(0),
        )
    }
    val season = seasons[selected.coerceIn(seasons.indices)]
    val titles = seasons.map { seasonLabel(it) }
    // The state of the focused card when it cannot play: the hint under the row explains it.
    var focusedUnavailable by remember { mutableStateOf<EpisodeState?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = ScreenPaddingH)) {
            if (seasons.size > 1) {
                SegmentedPills(options = titles, selectedIndex = selected, onSelect = { selected = it }, selectOnFocus = true)
            } else {
                Text(titles.first(), style = Antresol.typography.sectionTitle)
            }
        }
        // Each season gets its own scroll state and focus restorer, starting at the episode to watch.
        key(season.number) {
            SeasonRow(
                season, guide.fromServer, initialEpisodeId, focus, onPlayEpisode, onOpenEpisode, episodeWays, onPlayEpisodeWay, onEpisodeWays, episodeBusy,
                onFocusUnavailable = { focusedUnavailable = it },
            )
        }
        Text(
            stringResource(
                when (focusedUnavailable) {
                    EpisodeState.NotDownloaded -> R.string.episode_not_in_library
                    EpisodeState.Upcoming -> R.string.episode_not_aired
                    else -> TvR.string.detail_episode_hold_hint
                },
            ),
            style = Antresol.typography.caption,
            modifier = Modifier.padding(horizontal = ScreenPaddingH),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeasonRow(
    season: GuideSeason,
    guided: Boolean,
    initialEpisodeId: Long?,
    focus: FocusMemory,
    onPlayEpisode: (EpisodeCard) -> Unit,
    onOpenEpisode: (EpisodeCard) -> Unit,
    episodeWays: (GuideEpisode) -> List<AddonWay>,
    onPlayEpisodeWay: (GuideEpisode, List<AddonWay>) -> Unit,
    onEpisodeWays: (GuideEpisode, List<AddonWay>) -> Unit,
    episodeBusy: (GuideEpisode) -> Boolean,
    onFocusUnavailable: (EpisodeState?) -> Unit,
) {
    val episodes = season.episodes
    val start = episodes.indexOfFirst { it.card?.id == initialEpisodeId && initialEpisodeId != null }.takeIf { it >= 0 }
        ?: episodes.indexOfFirst { it.isPlayable && it.progress > 0.0 && !it.watched }.takeIf { it >= 0 }
        ?: episodes.indexOfFirst { it.isPlayable }.coerceAtLeast(0)
    HorizontalRowScrolling {
        LazyRow(
            state = rememberLazyListState(initialFirstVisibleItemIndex = start),
            contentPadding = PaddingValues(horizontal = ScreenPaddingH, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .trapHorizontalFocus()
                .focusRestorer(),
        ) {
            items(episodes, key = { it.key }) { episode ->
                val card = episode.card?.takeIf { episode.isPlayable }
                val ways = episodeWays(episode)
                EpisodeTile(
                    episode = episode,
                    mark = episodeStateLabel(episode, guided),
                    busy = episodeBusy(episode),
                    // A way that plays it makes the card as bright as a library one.
                    playable = ways.isNotEmpty(),
                    // An episode the library does not have plays through the addons' ways (the title's way), when there are any.
                    onClick = { if (card != null) onPlayEpisode(card) else if (ways.isNotEmpty()) onPlayEpisodeWay(episode, ways) },
                    // Hold OK: the episode's page, with its ways beside it when there are any.
                    onLongClick = { if (ways.isNotEmpty()) onEpisodeWays(episode, ways) else card?.let(onOpenEpisode) },
                    surfaceModifier = Modifier
                        .testTag("episode-${episode.season}-${episode.number}")
                        .remembersFocus(focus, episodeFocusKey(episode))
                        .onFocusChanged { if (it.isFocused) onFocusUnavailable(if (card == null && ways.isEmpty()) episode.state else null) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeTile(
    episode: GuideEpisode,
    mark: String?,
    busy: Boolean,
    playable: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    surfaceModifier: Modifier,
) {
    val urls = LocalMediaUrls.current
    val outline = AntresolShapes.card
    val card = episode.card
    val title = episode.title.ifBlank { stringResource(R.string.detail_episode_number, episode.number) }
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(CardSizes.wideWidth)
            .alpha(EpisodeLabels.alpha(episode.state, playable)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            outline = outline,
            modifier = surfaceModifier
                .fillMaxWidth()
                .aspectRatio(AntresolMotion.BACKDROP_ASPECT)
                .onFocusChanged { focused = it.hasFocus },
        ) {
            ArtworkImage(
                // The episode's own still or a frame of it; the series backdrop only when the server has neither.
                url = card?.let { urls?.image(it.wideImage) },
                fallbackUrl = card?.let { urls?.image(it.wideFallback) },
                title = title,
                shape = outline,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(WatchedStyle.artworkAlpha(episode.watched, focused)),
            )
            if (episode.watched) {
                WatchedMarkOverlay()
            } else if (episode.progress > 0.0) {
                ProgressBar(
                    fraction = episode.progress.toFloat(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.detail_episode_title, episode.number, title),
            style = Antresol.typography.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (busy) {
            Text(stringResource(R.string.way_busy), style = Antresol.typography.caption, modifier = Modifier.testTag("episode-way-busy"))
        } else {
            EpisodeMarks(isNew = episode.isNew, state = mark)
        }
        card?.durationMs?.takeIf { it > 0 }?.let {
            Text(spokenDuration(it), style = Antresol.typography.caption, maxLines = 1)
        }
        card?.overview?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = Antresol.typography.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

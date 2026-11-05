package app.antresol.mobile.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Track
import app.antresol.mobile.R
import app.antresol.mobile.playback.PlaybackConnection
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.components.BackBar
import app.antresol.mobile.ui.components.EmptyView
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.PageTitle
import app.antresol.mobile.ui.components.PagingFooter
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.TrackRow
import app.antresol.mobile.ui.load
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.AntresolColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.antresol.ui.R as UiR

/**
 * Every song (`/api/music/songs`), paged; [starred] lists the starred ones only. Playing from this
 * page queues the whole list: the pages not loaded yet are fetched and appended in the background.
 */
class SongsViewModel(private val client: AntresolClient, private val starred: Boolean) : ViewModel() {
    data class Songs(val tracks: List<Track>, val total: Int) {
        val hasMore: Boolean get() = tracks.size < total
    }

    var state: LoadState<Songs> by mutableStateOf(LoadState.Loading)
        private set

    /** The last page failed to load; the list shows "Try Again" at its end. */
    var pageError: Throwable? by mutableStateOf(null)
        private set

    var loadingMore: Boolean by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var queueJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        job?.cancel()
        queueJob?.cancel()
        pageError = null
        state = LoadState.Loading
        job = viewModelScope.launch {
            state = load { client.songs(starred = starred, offset = 0).let { Songs(it.items, it.total) } }
        }
    }

    /** Fetches the next page; [retry] after a failed page ("Try Again"). */
    fun loadMore(retry: Boolean = false) {
        if (pageError != null && !retry) return
        val songs = (state as? LoadState.Ready)?.value ?: return
        // While a play queues the remaining pages, they arrive through it.
        if (!songs.hasMore || job?.isActive == true || queueJob?.isActive == true) return
        pageError = null
        loadingMore = true
        job = viewModelScope.launch {
            try {
                nextPage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pageError = e
            } finally {
                loadingMore = false
            }
        }
    }

    /**
     * Plays the list from [index] ([shuffled]: in random order) and keeps appending the pages not
     * loaded yet to the same queue.
     */
    fun play(playback: PlaybackConnection, index: Int = 0, shuffled: Boolean = false) {
        val songs = (state as? LoadState.Ready)?.value ?: return
        val generation = playback.playTracks(songs.tracks, index, shuffled) ?: return
        queueJob?.cancel()
        queueJob = viewModelScope.launch {
            // Wait for a page already loading, then fetch the rest page by page.
            job?.join()
            var queued = songs.tracks.size
            while (true) {
                val current = (state as? LoadState.Ready)?.value ?: return@launch
                if (current.tracks.size > queued) {
                    playback.appendTracks(generation, current.tracks.subList(queued, current.tracks.size))
                    queued = current.tracks.size
                }
                if (!current.hasMore) return@launch
                try {
                    nextPage()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pageError = e
                    return@launch
                }
            }
        }
    }

    /** Appends the next page to [state]; a page without new songs means the total was stale. */
    private suspend fun nextPage() {
        val songs = (state as? LoadState.Ready)?.value ?: return
        val fresh = client.songs(starred = starred, offset = songs.tracks.size).let { it.items to it.total }
        val total = if (fresh.first.isEmpty()) songs.tracks.size else fresh.second
        state = LoadState.Ready(Songs(songs.tracks + fresh.first, total))
    }
}

/** The Songs page: Play and Shuffle for everything loaded, then one row per song. */
@Composable
fun SongsScreen(viewModel: SongsViewModel, title: String, playback: PlaybackConnection, onBack: () -> Unit) {
    val bottom = LocalBottomBarPadding.current
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 10 } == true } }
    LaunchedEffect(nearEnd, viewModel.state) { if (nearEnd) viewModel.loadMore() }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().background(AntresolColors.Background).testTag("songs"),
        contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + 16.dp),
    ) {
        item("top") { BackBar(onBack) }
        item("title") { PageTitle(title) }
        when (val state = viewModel.state) {
            LoadState.Loading -> item("loading") { LoadingView() }
            is LoadState.Failed -> item("error") { ErrorView(state.error, onRetry = viewModel::reload) }
            is LoadState.Ready -> {
                val tracks = state.value.tracks
                if (tracks.isEmpty()) {
                    item("empty") { EmptyView(stringResource(UiR.string.state_empty)) }
                } else {
                    item("actions") {
                        PlayShuffleRow(onPlay = { viewModel.play(playback) }, onShuffle = { viewModel.play(playback, shuffled = true) })
                    }
                    itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                        TrackRow(
                            track = track,
                            number = null,
                            showArtist = true,
                            playing = playback.current?.trackId == track.id,
                            onClick = { viewModel.play(playback, index) },
                            showCover = true,
                        )
                    }
                    item("footer") {
                        PagingFooter(viewModel.pageError, viewModel.loadingMore, onRetry = { viewModel.loadMore(retry = true) })
                    }
                }
            }
        }
    }
}

/**
 * The white Play pill and the glass Shuffle pill, side by side; without [onPlay] only Shuffle.
 * [trailing] adds round buttons after them (an album's favorite star).
 */
@Composable
fun PlayShuffleRow(
    onPlay: (() -> Unit)?,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    shuffleEnabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onPlay != null) {
            PrimaryPillButton(
                text = stringResource(UiR.string.action_play),
                icon = AntresolIcons.Play,
                onClick = onPlay,
                modifier = Modifier.weight(1f).testTag("play-all"),
            )
        }
        GlassPillButton(
            text = stringResource(UiR.string.action_shuffle),
            icon = MobileIcons.Shuffle,
            onClick = onShuffle,
            enabled = shuffleEnabled,
            modifier = Modifier.weight(1f).testTag("shuffle-all"),
        )
        trailing()
    }
}

/** "Shuffle All" on the Music page. */
@Composable
fun ShuffleAllButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    GlassPillButton(
        text = stringResource(R.string.music_shuffle_all),
        icon = MobileIcons.Shuffle,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.padding(horizontal = PageGutter, vertical = 8.dp).testTag("music-shuffle-all"),
    )
}

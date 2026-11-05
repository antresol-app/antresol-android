package app.antresol.mobile.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Page
import app.antresol.core.model.Track
import app.antresol.mobile.R
import app.antresol.mobile.playback.PlaybackConnection
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.PagedViewModel
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
import app.antresol.mobile.ui.playlists.PlaylistPicker
import app.antresol.mobile.ui.playlists.PlaylistPickerHost
import app.antresol.ui.R as UiR
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.AntresolColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Every song (`/api/music/songs`), paged; [starred] lists the starred ones only. Playing from this
 * page queues the whole list: the pages not loaded yet are fetched and appended while the page is
 * open ([followToEnd]: failed pages are retried, and pages that arrive later still join the queue).
 */
class SongsViewModel(private val client: AntresolClient, private val starred: Boolean) : PagedViewModel<Track>() {
    private var queueJob: Job? = null

    /** A long press adds a song to a playlist. */
    val playlistPicker = PlaylistPicker(client, viewModelScope)

    init {
        reload()
    }

    override suspend fun fetch(offset: Int): Page<Track> = client.songs(starred = starred, offset = offset)

    override fun keyOf(item: Track): Any = item.id

    override fun reload() {
        queueJob?.cancel()
        super.reload()
    }

    /**
     * Plays the list from [index] ([shuffled]: in random order) and keeps appending the pages not
     * loaded yet to the same queue, until the list is complete or another queue replaces it.
     */
    fun play(playback: PlaybackConnection, index: Int = 0, shuffled: Boolean = false) {
        val songs = (state as? LoadState.Ready)?.value ?: return
        val generation = playback.playTracks(songs.items, index, shuffled) ?: return
        queueJob?.cancel()
        queueJob = viewModelScope.launch {
            followToEnd(songs.items.size, wanted = { playback.isCurrentQueue(generation) }) { tracks ->
                playback.appendTracks(generation, tracks)
            }
        }
    }
}

/** The Songs page: Play and Shuffle for the whole list, then one row per song. */
@Composable
fun SongsScreen(viewModel: SongsViewModel, title: String, playback: PlaybackConnection, onBack: () -> Unit) {
    val bottom = LocalBottomBarPadding.current
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 10 } == true } }
    LaunchedEffect(nearEnd, viewModel.state) { if (nearEnd) viewModel.loadMore() }
    val snackbar = remember { SnackbarHostState() }
    PlaylistPickerHost(viewModel.playlistPicker, snackbar)
    Box(Modifier.fillMaxSize()) {
        SongsList(viewModel, title, playback, onBack, list)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom))
    }
}

@Composable
private fun SongsList(viewModel: SongsViewModel, title: String, playback: PlaybackConnection, onBack: () -> Unit, list: LazyListState) {
    val bottom = LocalBottomBarPadding.current
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
                val tracks = state.value.items
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
                            onLongClick = { viewModel.playlistPicker.open(listOf(track.id)) },
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

/** The white Play pill and the glass Shuffle pill, side by side; without [onPlay] only Shuffle. */
@Composable
fun PlayShuffleRow(
    onPlay: (() -> Unit)?,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    shuffleEnabled: Boolean = true,
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

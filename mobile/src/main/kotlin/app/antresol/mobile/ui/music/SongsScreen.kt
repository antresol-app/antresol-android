package app.antresol.mobile.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.TrackRow
import app.antresol.mobile.ui.load
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.AntresolColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.antresol.ui.R as UiR

/** Every song (`/api/music/songs`), paged; [starred] lists the starred ones only. */
class SongsViewModel(private val client: AntresolClient, private val starred: Boolean) : ViewModel() {
    data class Songs(val tracks: List<Track>, val total: Int) {
        val hasMore: Boolean get() = tracks.size < total
    }

    var state: LoadState<Songs> by mutableStateOf(LoadState.Loading)
        private set

    private var job: Job? = null

    init {
        reload()
    }

    fun reload() {
        job?.cancel()
        state = LoadState.Loading
        job = viewModelScope.launch {
            state = load { client.songs(starred = starred, offset = 0).let { Songs(it.items, it.total) } }
        }
    }

    fun loadMore() {
        val songs = (state as? LoadState.Ready)?.value ?: return
        if (!songs.hasMore || job?.isActive == true) return
        job = viewModelScope.launch {
            val next = load { client.songs(starred = starred, offset = songs.tracks.size) }
            if (next is LoadState.Ready) {
                val fresh = next.value.items
                val total = if (fresh.isEmpty()) songs.tracks.size else next.value.total
                state = LoadState.Ready(Songs(songs.tracks + fresh, total))
            }
        }
    }
}

/** The Songs page: Play and Shuffle for everything loaded, then one row per song. */
@Composable
fun SongsScreen(viewModel: SongsViewModel, title: String, playback: PlaybackConnection, onBack: () -> Unit) {
    val bottom = LocalBottomBarPadding.current
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 10 } == true } }
    LaunchedEffect(nearEnd) { if (nearEnd) viewModel.loadMore() }
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
                        PlayShuffleRow(onPlay = { playback.playTracks(tracks) }, onShuffle = { playback.playTracks(tracks, shuffled = true) })
                    }
                    itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                        TrackRow(
                            track = track,
                            number = null,
                            showArtist = true,
                            playing = playback.current?.trackId == track.id,
                            onClick = { playback.playTracks(tracks, index) },
                            showCover = true,
                        )
                    }
                }
            }
        }
    }
}

/** The white Play pill and the glass Shuffle pill, side by side. */
@Composable
fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrimaryPillButton(
            text = stringResource(UiR.string.action_play),
            icon = AntresolIcons.Play,
            onClick = onPlay,
            modifier = Modifier.weight(1f).testTag("play-all"),
        )
        GlassPillButton(
            text = stringResource(UiR.string.action_shuffle),
            icon = MobileIcons.Shuffle,
            onClick = onShuffle,
            modifier = Modifier.weight(1f).testTag("shuffle-all"),
        )
    }
}

/** "Shuffle All" on the Music page. */
@Composable
fun ShuffleAllButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassPillButton(
        text = stringResource(R.string.music_shuffle_all),
        icon = MobileIcons.Shuffle,
        onClick = onClick,
        modifier = modifier.padding(horizontal = PageGutter, vertical = 8.dp).testTag("music-shuffle-all"),
    )
}

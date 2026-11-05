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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Page
import app.antresol.core.model.Track
import app.antresol.mobile.R
import app.antresol.mobile.playback.PlaybackConnection
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.LocalNotices
import app.antresol.mobile.ui.NothingToPlayException
import app.antresol.mobile.ui.PagedViewModel
import app.antresol.mobile.ui.playbackMessage
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import app.antresol.ui.R as UiR

/** Every song (`/api/music/songs`), paged; [starred] lists the starred ones only. */
class SongsViewModel(
    private val client: AntresolClient,
    private val starred: Boolean,
    /** Outlives the page, so queueing the rest of the library continues after leaving it. */
    private val appScope: CoroutineScope,
) : PagedViewModel<Track>() {
    init {
        reload()
    }

    override suspend fun fetch(offset: Int): Page<Track> = client.songs(starred = starred, offset = offset)

    override fun keyOf(item: Track): Any = item.id

    /**
     * Plays the whole list from [startIndex]: the loaded songs start at once and the remaining
     * pages are appended to the queue as they arrive.
     */
    fun playAll(playback: PlaybackConnection, startIndex: Int = 0) {
        val list = (state as? LoadState.Ready)?.value ?: return
        playback.playTracks(list.items, startIndex)
        if (!list.hasMore) return
        appScope.launch {
            var offset = list.items.size
            try {
                while (offset < MAX_QUEUE) {
                    val page = fetch(offset)
                    if (page.items.isEmpty()) break
                    playback.appendTracks(page.items)
                    offset += page.items.size
                    if (offset >= page.total) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The songs queued so far keep playing.
            }
        }
    }

    /**
     * Shuffles the whole list: the server's library shuffle for all songs, every page for the
     * starred ones. Calls [onResult] with an error, or null when something plays.
     */
    fun shuffleAll(playback: PlaybackConnection, onResult: (Throwable?) -> Unit) {
        appScope.launch {
            val tracks = load {
                if (!starred) {
                    client.shuffle().tracks
                } else {
                    val all = ArrayList<Track>()
                    do {
                        val page = fetch(all.size)
                        all += page.items
                    } while (page.items.isNotEmpty() && all.size < page.total && all.size < MAX_QUEUE)
                    all
                }
            }
            when (tracks) {
                is LoadState.Ready -> {
                    playback.playTracks(tracks.value, shuffled = true)
                    onResult(if (tracks.value.isEmpty()) NothingToPlayException() else null)
                }
                is LoadState.Failed -> onResult(tracks.error)
                LoadState.Loading -> Unit
            }
        }
    }

    private companion object {
        const val MAX_QUEUE = 2_000
    }
}

/** The Songs page: Play and Shuffle for the whole list, then one row per song. */
@Composable
fun SongsScreen(viewModel: SongsViewModel, title: String, playback: PlaybackConnection, onBack: () -> Unit) {
    val bottom = LocalBottomBarPadding.current
    val notices = LocalNotices.current
    val resources = LocalContext.current.resources
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 10 } == true } }
    // A failed page waits for "Try Again" instead of retrying on every layout pass.
    LaunchedEffect(nearEnd, viewModel.state) { if (nearEnd && viewModel.pagingError == null) viewModel.loadMore() }
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
                        PlayShuffleRow(
                            onPlay = { viewModel.playAll(playback) },
                            onShuffle = {
                                viewModel.shuffleAll(playback) { error -> error?.let { notices.show(it.playbackMessage(resources)) } }
                            },
                        )
                    }
                    itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                        TrackRow(
                            track = track,
                            number = null,
                            showArtist = true,
                            playing = playback.current?.trackId == track.id,
                            onClick = { viewModel.playAll(playback, index) },
                            showCover = true,
                        )
                    }
                    item("paging") { PagingFooter(viewModel.loadingMore, viewModel.pagingError, onRetry = viewModel::loadMore) }
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

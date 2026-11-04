package app.antresol.tv.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.ShuffleSource
import app.antresol.core.model.Track
import app.antresol.tv.R as TvR
import app.antresol.tv.playback.AudioEngine
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.TrackRow
import app.antresol.tv.ui.components.TvIcons
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.tv.ui.load
import app.antresol.tv.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.format.Durations
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Every song of the library, paged, with Play and Shuffle. */
class SongsViewModel(
    private val client: AntresolClient,
    private val audio: AudioEngine,
    private val appScope: CoroutineScope,
) : ViewModel() {
    data class Songs(val tracks: List<Track>, val total: Int) {
        val hasMore: Boolean get() = tracks.size < total
    }

    var state: LoadState<Songs> by mutableStateOf(LoadState.Loading)
        private set

    /** A failed Shuffle request, shown under the header. */
    var actionError: Throwable? by mutableStateOf(null)
        private set

    private var job: Job? = null

    init {
        reload()
    }

    fun reload() {
        job?.cancel()
        state = LoadState.Loading
        job = viewModelScope.launch { state = load { client.songs().let { Songs(it.items, it.total) } } }
    }

    fun loadMore() {
        val songs = (state as? LoadState.Ready)?.value ?: return
        if (!songs.hasMore || job?.isActive == true) return
        job = viewModelScope.launch {
            val next = load { client.songs(offset = songs.tracks.size) }
            if (next is LoadState.Ready) state = LoadState.Ready(Songs(songs.tracks + next.value.items, next.value.total))
        }
    }

    /** Plays the library from [index]; the pages not loaded yet are fetched and queued behind it. */
    fun play(index: Int) {
        val songs = (state as? LoadState.Ready)?.value ?: return
        val queue = audio.playTracks(songs.tracks, index) ?: return
        if (!songs.hasMore) return
        appScope.launch {
            var offset = songs.tracks.size
            while (offset < songs.total) {
                val page = try {
                    client.songs(offset = offset)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch // The queue just ends earlier; playing what is loaded matters more.
                }
                if (page.items.isEmpty()) return@launch
                audio.appendTracks(queue, page.items)
                offset += page.items.size
            }
        }
    }

    /** Shuffles the whole library on the server side (not only the loaded page). */
    fun shuffleAll(onStarted: () -> Unit) {
        actionError = null
        viewModelScope.launch {
            when (val queue = load { client.shuffle(ShuffleSource.All).tracks }) {
                is LoadState.Ready -> if (queue.value.isNotEmpty()) {
                    audio.playTracks(queue.value)
                    onStarted()
                }
                is LoadState.Failed -> actionError = queue.error
                LoadState.Loading -> Unit
            }
        }
    }
}

/** The Songs page: header with Play / Shuffle, then one focusable row per song. */
@Composable
fun SongsScreen(viewModel: SongsViewModel, audio: AudioEngine, onNowPlaying: () -> Unit, onScrolledToTop: (Boolean) -> Unit) {
    val focus = rememberFocusMemory()
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }
    val state = viewModel.state
    val songs = (state as? LoadState.Ready)?.value
    LaunchedEffect(songs != null) {
        if (songs != null && songs.tracks.isNotEmpty()) focus.restore("song/${songs.tracks.first().id}/0")
    }
    VerticalShelfScrolling(leadingFraction = 0.25f, trailingFraction = 0.2f) {
        LazyColumn(
            state = listState,
            // Room for the top navigation above the title.
            contentPadding = PaddingValues(start = ScreenPaddingH, end = ScreenPaddingH, top = 84.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(TvR.string.music_songs), style = Antresol.typography.pageTitle)
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.trapHorizontalFocus()) {
                            PillButton(
                                text = stringResource(R.string.action_play),
                                onClick = {
                                    viewModel.play(0)
                                    onNowPlaying()
                                },
                                icon = AntresolIcons.Play,
                                style = PillStyle.Primary,
                                enabled = !songs?.tracks.isNullOrEmpty(),
                            )
                            PillButton(
                                text = stringResource(R.string.action_shuffle),
                                onClick = { viewModel.shuffleAll(onNowPlaying) },
                                icon = TvIcons.Shuffle,
                                enabled = !songs?.tracks.isNullOrEmpty(),
                            )
                        }
                    }
                    viewModel.actionError?.let { error ->
                        Text(error.userMessage(LocalContext.current.resources), style = Antresol.typography.caption, color = AntresolColors.Red)
                    }
                }
            }
            when (state) {
                LoadState.Loading -> item(key = "loading") { LoadingView(Modifier.padding(top = 120.dp)) }
                is LoadState.Failed -> item(key = "error") { ErrorView(state.error, onRetry = viewModel::reload, modifier = Modifier.padding(top = 60.dp)) }
                is LoadState.Ready -> {
                    val tracks = state.value.tracks
                    if (tracks.isEmpty()) item(key = "empty") { MessageView(stringResource(TvR.string.music_no_songs), Modifier.padding(top = 120.dp)) }
                    itemsIndexed(tracks, key = { index, track -> "song/${track.id}/$index" }) { index, track ->
                        TrackRow(
                            leading = (index + 1).toString(),
                            title = track.title,
                            subtitle = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                            trailing = Durations.clock(track.durationMs),
                            playing = audio.currentTrack?.id == track.id,
                            onClick = {
                                viewModel.play(index)
                                onNowPlaying()
                            },
                            modifier = Modifier
                                .remembersFocus(focus, "song/${track.id}/$index")
                                .onFocusChanged { if (it.isFocused && index >= tracks.size - PREFETCH) viewModel.loadMore() },
                        )
                    }
                }
            }
        }
    }
}

private const val PREFETCH = 20

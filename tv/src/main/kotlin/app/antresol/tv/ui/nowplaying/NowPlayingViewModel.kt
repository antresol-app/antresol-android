package app.antresol.tv.ui.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Lyrics
import app.antresol.tv.playback.AudioEngine
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Lyrics of the current song and the favorite toggle; playback itself lives in [AudioEngine]. */
class NowPlayingViewModel(private val client: AntresolClient, val audio: AudioEngine) : ViewModel() {
    /** Lyrics of the current track: Ready(null) when it has none. */
    var lyrics: LoadState<Lyrics?> by mutableStateOf(LoadState.Ready(null))
        private set

    /** A failed favorite toggle. */
    var actionError: Throwable? by mutableStateOf(null)
        private set

    init {
        viewModelScope.launch {
            snapshotFlow { audio.currentTrack?.let { it.id to it.hasLyrics } }
                .distinctUntilChanged()
                .collectLatest { current ->
                    val (trackId, hasLyrics) = current ?: (0L to false)
                    lyrics = if (!hasLyrics) LoadState.Ready(null) else LoadState.Loading
                    if (hasLyrics) lyrics = load { client.lyrics(trackId) }
                }
        }
    }

    fun toggleStar() {
        val track = audio.currentTrack ?: return
        val star = !track.starred
        audio.updateTrack(track.copy(starred = star))
        actionError = null
        viewModelScope.launch {
            try {
                if (star) client.star(track.id) else client.unstar(track.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audio.updateTrack(track)
                actionError = e
            }
        }
    }
}

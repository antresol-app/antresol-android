package app.antresol.mobile.ui.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Track
import app.antresol.mobile.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** "Shuffle All" on the Music page: one request at a time, with a notice when it fails or finds nothing. */
class ShuffleAllViewModel(private val client: AntresolClient) : ViewModel() {
    var busy: Boolean by mutableStateOf(false)
        private set

    /** A string resource to show once; the screen calls [consumeNotice]. */
    var notice: Int? by mutableStateOf(null)
        private set

    fun consumeNotice() {
        notice = null
    }

    fun shuffle(play: (List<Track>) -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val tracks = client.shuffle().tracks
                if (tracks.isEmpty()) notice = R.string.music_nothing_to_shuffle else play(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice = R.string.music_playback_failed
            } finally {
                busy = false
            }
        }
    }
}

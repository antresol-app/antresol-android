package app.antresol.tv.ui.comics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.BookDetail
import app.antresol.core.model.ComicPages
import app.antresol.core.reading.ComicProgress
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A comic volume ready to read: its detail, reader file and page sizes. */
data class ComicVolume(val detail: BookDetail, val fileId: Long, val pages: ComicPages) {
    val count: Int get() = pages.count
    val isRightToLeft: Boolean get() = pages.isRightToLeft || detail.direction == "rtl"
}

/**
 * Reads one comic volume page by page. The position is saved as `page:<n>` a moment after the
 * page changes and once more when the reader closes (on the app scope, so it survives the screen).
 */
class ComicReaderViewModel(
    private val client: AntresolClient,
    private val appScope: CoroutineScope,
    private val volumeId: Long,
    private val fromStart: Boolean = false,
) : ViewModel() {
    var state: LoadState<ComicVolume> by mutableStateOf(LoadState.Loading)
        private set

    /** The 0-based page shown. */
    var page: Int by mutableIntStateOf(0)
        private set

    /** The reader went past the last page: show the end-of-volume prompt. */
    var atEnd: Boolean by mutableStateOf(false)
        private set

    private var savedPage: Int? = null
    private var saveJob: Job? = null

    /** Writes as the profile that opened the reader, even when the last one runs after a profile switch. */
    private val writer: AntresolClient = client.pinned()

    init {
        reload()
    }

    fun reload() {
        state = LoadState.Loading
        viewModelScope.launch {
            state = load {
                val detail = client.book(volumeId)
                val fileId = detail.readFileId.takeIf { it > 0 } ?: detail.volumes.firstOrNull()?.fileId ?: 0L
                val pages = if (fileId > 0) client.comicPages(fileId) else ComicPages()
                ComicVolume(detail, fileId, pages).also { volume ->
                    val progress = detail.progress
                    page = if (fromStart) 0 else ComicProgress.startPage(progress?.locator, progress?.finished == true, volume.count)
                    savedPage = page.takeIf { progress != null && progress.fraction > 0 }
                }
            }
        }
    }

    fun next() {
        val volume = (state as? LoadState.Ready)?.value ?: return
        if (page < volume.count - 1) show(page + 1) else atEnd = true
    }

    fun previous() {
        if (atEnd) {
            atEnd = false
            return
        }
        if (page > 0) show(page - 1)
    }

    fun dismissEnd() {
        atEnd = false
    }

    private fun show(index: Int) {
        page = index
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            save()
        }
    }

    private suspend fun save() {
        val volume = (state as? LoadState.Ready)?.value ?: return
        val current = page
        if (current == savedPage || volume.count == 0) return
        runCatching { writer.saveProgress(volumeId, ComicProgress.update(current, volume.count)) }
            .onSuccess { savedPage = current }
    }

    override fun onCleared() {
        appScope.launch { save() }
    }

    private companion object {
        const val SAVE_DELAY_MS = 1_200L
    }
}

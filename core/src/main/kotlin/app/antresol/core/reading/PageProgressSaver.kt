package app.antresol.core.reading

import app.antresol.core.api.attempt
import app.antresol.core.model.ProgressUpdate
import app.antresol.core.playback.PendingWrites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Saves the page of a reader (comics, PDF books) as `page:<n>`: [delayMs] after the page changes,
 * on the reader's [scope], and once more when the reader closes, on [appScope] so it survives the
 * screen. That last save is tracked in [pendingWrites], so the page behind the reader shows the
 * new position after it. [write] should send as the profile that opened the reader.
 */
class PageProgressSaver(
    private val scope: CoroutineScope,
    private val appScope: CoroutineScope,
    private val pendingWrites: PendingWrites,
    private val delayMs: Long,
    private val write: suspend (ProgressUpdate) -> Unit,
) {
    private var page = -1
    private var count = 0
    private var savedPage = -1
    private var job: Job? = null

    /**
     * The reader opened on page [index] (0-based) of [total]. With [onServer] the server already
     * holds that page and it is not written again; without, closing the reader writes it.
     */
    fun opened(index: Int, total: Int, onServer: Boolean) {
        page = index
        count = total
        if (onServer) savedPage = index
    }

    /** The reader shows page [index] (0-based) of [total]. */
    fun onPage(index: Int, total: Int) {
        if (index == page) return
        page = index
        count = total
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            save()
        }
    }

    /** The reader closes: the page is written if it is not yet, then [then] runs (also with nothing to write). */
    fun close(then: (suspend () -> Unit)? = null) {
        if (then == null && (page < 0 || page == savedPage)) return
        pendingWrites.track(
            appScope.launch {
                save()
                then?.invoke()
            },
        )
    }

    /** A failed write is retried with the next page or on close. */
    private suspend fun save() {
        val current = page
        if (current < 0 || current == savedPage || count <= 0) return
        if (attempt { write(ComicProgress.update(current, count)) } != null) savedPage = current
    }
}

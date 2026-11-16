package app.antresol.mobile.reader

import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.ComicPages
import app.antresol.core.playback.PendingWrites
import app.antresol.core.reading.ComicProgress
import app.antresol.core.reading.PageProgressSaver
import app.antresol.ui.LoadViewModel
import kotlinx.coroutines.CoroutineScope

/** One comic volume ready to read. */
data class ComicVolume(
    val volumeId: Long,
    val title: String,
    val subtitle: String,
    val fileId: Long,
    val pages: ComicPages,
    val startPage: Int,
    /** The next volume of the series, or 0. */
    val nextId: Long,
)

/** A reader file that the server cannot open (no file id). */
class NothingToReadException : Exception()

/**
 * Loads a comic volume (`/api/books/{id}` → `readFileId` → `/api/comics/{fileId}/pages`) and saves
 * the page as `page:<n>` a moment after it changes and when the reader closes. [client] should be
 * pinned to the profile that opened the volume; the final save is tracked in [pendingWrites].
 */
class ComicReaderViewModel(
    client: AntresolClient,
    appScope: CoroutineScope,
    pendingWrites: PendingWrites,
    volumeId: Long,
    fromStart: Boolean,
) : LoadViewModel<ComicVolume>(fetch = { load(client, volumeId, fromStart) }) {
    private val saver = PageProgressSaver(viewModelScope, appScope, pendingWrites, SAVE_DELAY_MS) { client.saveProgress(volumeId, it) }

    /** The reader shows page [index] (0-based) of [total]. */
    fun onPage(index: Int, total: Int) = saver.onPage(index, total)

    override fun onCleared() = saver.close()

    private companion object {
        const val SAVE_DELAY_MS = 1_500L

        suspend fun load(client: AntresolClient, volumeId: Long, fromStart: Boolean): ComicVolume {
            val book = client.book(volumeId)
            val fileId = book.readFileId.takeIf { it > 0 } ?: book.files.firstOrNull()?.id ?: throw NothingToReadException()
            val pages = client.comicPages(fileId)
            if (pages.count <= 0) throw NothingToReadException()
            val progress = book.progress
            return ComicVolume(
                volumeId = volumeId,
                title = book.series?.name?.ifBlank { null } ?: book.card.title,
                subtitle = book.card.subtitle,
                fileId = fileId,
                pages = pages,
                startPage = if (fromStart) 0 else ComicProgress.startPage(progress?.locator, progress?.finished == true, pages.count),
                nextId = book.nextId,
            )
        }
    }
}

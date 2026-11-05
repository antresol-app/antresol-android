package app.antresol.mobile.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.playback.PendingWrites
import app.antresol.core.reading.ComicProgress
import app.antresol.mobile.ui.LoadViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** An open PDF: page sizes in points and on-demand rendering (PdfRenderer allows one page at a time). */
class PdfDocument(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val lock = Mutex()
    private var closed = false

    /** `width / height` of every page. */
    val aspectRatios: List<Float> = List(renderer.pageCount) { index ->
        renderer.openPage(index).use { page -> page.width.toFloat() / page.height.coerceAtLeast(1) }
    }

    val pageCount: Int get() = aspectRatios.size

    /** Renders page [index] [widthPx] wide on white. */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed) return@withLock null
            renderer.openPage(index).use { page ->
                val width = widthPx.coerceIn(1, MAX_WIDTH_PX)
                val height = (width * page.height.toFloat() / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    /**
     * Closes the renderer once no page render is running (PdfRenderer throws when closed with a page
     * open). Never throws: it runs on the app scope after the reader is gone.
     */
    suspend fun closeSafely() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed) return@withLock
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    /** Closes at once; only for a document nobody else has seen yet (it failed to open). */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    private companion object {
        const val MAX_WIDTH_PX = 2_400
    }
}

/** A book ready to read in the PDF reader. */
data class PdfBook(val bookId: Long, val title: String, val document: PdfDocument, val startPage: Int)

/**
 * Downloads a book's PDF (`/api/stream/{fileId}` with the Bearer header) into the cache, opens it
 * with [PdfRenderer], and saves the page as `page:<n>` like the comic reader. [client] should be
 * pinned to the profile that opened the book (the final save may run after a profile switch);
 * the final save is tracked in [pendingWrites] so the book page refreshes after it.
 */
class PdfReaderViewModel(
    private val client: AntresolClient,
    private val appScope: CoroutineScope,
    private val pendingWrites: PendingWrites,
    cacheDir: File,
    private val bookId: Long,
    fromStart: Boolean,
) : LoadViewModel<PdfBook>(fetch = { open(client, File(cacheDir, "books"), bookId, fromStart) }) {
    private var page = -1
    private var savedPage = -1
    private var saveJob: Job? = null

    fun onPage(index: Int, total: Int) {
        if (index == page) return
        page = index
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            save(total)
        }
    }

    override fun onCleared() {
        val book = (state as? app.antresol.mobile.ui.LoadState.Ready)?.value ?: return
        pendingWrites.track(
            appScope.launch {
                save(book.document.pageCount)
                book.document.closeSafely()
            },
        )
    }

    private suspend fun save(total: Int) {
        val current = page
        if (current < 0 || current == savedPage) return
        try {
            client.saveProgress(bookId, ComicProgress.update(current, total))
            savedPage = current
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Retried with the next page or on close.
        }
    }

    companion object {
        private const val SAVE_DELAY_MS = 1_500L

        private suspend fun open(client: AntresolClient, dir: File, bookId: Long, fromStart: Boolean): PdfBook {
            val book = client.book(bookId)
            val pdf = book.files.firstOrNull { it.format.equals("PDF", ignoreCase = true) } ?: throw NothingToReadException()
            val document = openCached(client, File(dir, serverFolder(client.baseUrl)), pdf.id, pdf.size)
            val progress = book.progress
            return PdfBook(
                bookId = bookId,
                title = book.card.title,
                document = document,
                startPage = if (fromStart) 0 else ComicProgress.startPage(progress?.locator, progress?.finished == true, document.pageCount),
            )
        }

        /** File ids are per server: each server gets its own cache folder. */
        private fun serverFolder(baseUrl: String): String =
            MessageDigest.getInstance("SHA-1").digest(baseUrl.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        /**
         * Opens the cached copy of [fileId], downloading it when missing, of another size than the
         * server reports (the file was replaced), or unreadable (a corrupt copy is fetched again once).
         */
        private suspend fun openCached(client: AntresolClient, dir: File, fileId: Long, size: Long): PdfDocument {
            val target = File(dir, "$fileId.pdf")
            val cached = withContext(Dispatchers.IO) {
                target.takeIf { it.length() > 0 && (size <= 0 || it.length() == size) }?.let { file ->
                    runCatching { PdfDocument(file) }.getOrElse {
                        file.delete()
                        null
                    }
                }
            }
            if (cached != null) return cached
            download(client, fileId, dir, target)
            return withContext(Dispatchers.IO) { PdfDocument(target) }
        }

        /** Downloads into a temporary file of its own, then moves it into place; a partial download never replaces a good copy. */
        private suspend fun download(client: AntresolClient, fileId: Long, dir: File, target: File) {
            val partial = withContext(Dispatchers.IO) {
                dir.mkdirs()
                File.createTempFile("$fileId-", ".part", dir)
            }
            try {
                client.download(fileId, partial)
                withContext(Dispatchers.IO) {
                    target.delete()
                    if (!partial.renameTo(target)) throw IOException("Could not store the book")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { partial.delete() }
            }
        }
    }
}

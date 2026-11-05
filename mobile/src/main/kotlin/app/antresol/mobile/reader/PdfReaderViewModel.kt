package app.antresol.mobile.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.model.BookDetail
import app.antresol.core.reading.ComicProgress
import app.antresol.mobile.ui.LoadViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException

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

    override fun close() {
        if (closed) return
        closed = true
        renderer.close()
        descriptor.close()
    }

    private companion object {
        const val MAX_WIDTH_PX = 2_400
    }
}

/** A book ready to read in the PDF reader. */
data class PdfBook(val bookId: Long, val title: String, val document: PdfDocument, val startPage: Int)

/**
 * Downloads a book's PDF (`/api/stream/{fileId}` with the Bearer header) into the cache, opens it
 * with [PdfRenderer], and saves the page as `page:<n>` like the comic reader.
 */
class PdfReaderViewModel(
    private val client: AntresolClient,
    private val appScope: CoroutineScope,
    cacheDir: File,
    private val bookId: Long,
) : LoadViewModel<PdfBook>(fetch = { open(client, File(cacheDir, "books"), bookId) }) {
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
        val book = (state as? app.antresol.mobile.ui.LoadState.Ready)?.value
        appScope.launch {
            book?.let { save(it.document.pageCount) }
            book?.document?.close()
        }
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

        /** The PDF file of a book, or null when the book has another format. */
        fun pdfFileId(book: BookDetail): Long? =
            book.files.firstOrNull { it.format.equals("PDF", ignoreCase = true) }?.id

        private suspend fun open(client: AntresolClient, dir: File, bookId: Long): PdfBook {
            val book = client.book(bookId)
            val fileId = pdfFileId(book) ?: throw NothingToReadException()
            val file = download(client, fileId, dir)
            val document = withContext(Dispatchers.IO) { PdfDocument(file) }
            val progress = book.progress
            return PdfBook(
                bookId = bookId,
                title = book.card.title,
                document = document,
                startPage = ComicProgress.startPage(progress?.locator, progress?.finished == true, document.pageCount),
            )
        }

        /** Caches the file once; a partial download never replaces a good copy. */
        private suspend fun download(client: AntresolClient, fileId: Long, dir: File): File = withContext(Dispatchers.IO) {
            val target = File(dir, "$fileId.pdf")
            if (target.length() > 0) return@withContext target
            dir.mkdirs()
            val partial = File(dir, "$fileId.pdf.part")
            val request = Request.Builder().url(client.urls.stream(fileId)).build()
            client.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, response.message)
                val body = response.body ?: throw IOException("Empty response")
                partial.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (!partial.renameTo(target)) throw IOException("Could not store the book")
            target
        }
    }
}

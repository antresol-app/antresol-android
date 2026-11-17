package app.antresol.mobile.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.playback.PendingWrites
import app.antresol.core.reading.ComicProgress
import app.antresol.core.reading.PageProgressSaver
import app.antresol.ui.LoadState
import app.antresol.ui.LoadViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    /**
     * The last few rendered pages (most recently used last), so scrolling back a page does not render
     * it again: as many as [pdfCachedPages] allows. Evicted bitmaps are left to the garbage
     * collector: a page still on screen may be drawing one.
     */
    private val rendered = LinkedHashMap<Pair<Int, Int>, Bitmap>(4, 0.75f, true)

    private val heapBytes = Runtime.getRuntime().maxMemory()

    /** The most pixels of a page here, see [pdfMaxPagePixels]. */
    private val maxPagePixels = pdfMaxPagePixels(heapBytes)

    /** Renders page [index] [widthPx] wide on white (smaller when the page would be over [maxPagePixels]). */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed) return@withLock null
            rendered[index to widthPx]?.let { return@withLock it }
            renderer.openPage(index).use { page ->
                val (width, height) = pdfRenderSize(page.width, page.height, widthPx, maxPagePixels)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    rendered[index to widthPx] = bitmap
                    val keep = pdfCachedPages(heapBytes, rendered.values.maxOf { it.allocationByteCount.toLong() })
                    val eldest = rendered.keys.iterator()
                    while (rendered.size > keep && eldest.hasNext()) {
                        eldest.next()
                        eldest.remove()
                    }
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
            rendered.clear()
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

}

/** The widest a PDF page is rendered. */
private const val MAX_PAGE_WIDTH_PX = 2_400

/** The most pixels of one rendered PDF page on a small heap: 16 MB as ARGB_8888. */
internal const val MAX_PAGE_PIXELS = 4_000_000

/**
 * The most pixels of one rendered PDF page for an app heap of [heapBytes]: an eighth of the heap as
 * ARGB_8888, never under [MAX_PAGE_PIXELS]. An A4 page at the full [MAX_PAGE_WIDTH_PX] is 2400×3396,
 * 8.15 MP or 32.6 MB: a 256 MB heap (what phones and tablets give an app) allows 8.39 MP, so it is
 * rendered whole; 192 MB gives 6.3 MP (A4 2108 px wide), 128 MB 4.2 MP (1721 px). The bitmap is
 * outside the Java heap (Android 8 on), the heap only tells how much memory the device has to spare.
 */
internal fun pdfMaxPagePixels(heapBytes: Long): Int = (heapBytes / 8 / 4).coerceIn(MAX_PAGE_PIXELS.toLong(), Int.MAX_VALUE.toLong()).toInt()

/**
 * How many rendered pages of [pageBytes] each are kept for an app heap of [heapBytes]: what fits a
 * quarter of the heap, one to three. So two of the pages cut by [pdfMaxPagePixels] or just under it
 * (A4 at 2400 px on a 256 MB heap: 65 MB), three of anything smaller (a phone's 1080 px page is
 * 6.6 MB) and of the largest pages from 384 MB on.
 */
internal fun pdfCachedPages(heapBytes: Long, pageBytes: Long): Int = (heapBytes / 4 / pageBytes.coerceAtLeast(1)).coerceIn(1, 3).toInt()

/**
 * The bitmap size of a [pageWidth] by [pageHeight] page (points) drawn [widthPx] wide: the page's
 * shape, at most [MAX_PAGE_WIDTH_PX] wide and at most [maxPixels] in all.
 */
internal fun pdfRenderSize(pageWidth: Int, pageHeight: Int, widthPx: Int, maxPixels: Int = MAX_PAGE_PIXELS): Pair<Int, Int> {
    val aspect = pageHeight.coerceAtLeast(1).toDouble() / pageWidth.coerceAtLeast(1)
    val widest = Math.sqrt(maxPixels / aspect).toInt()
    val width = widthPx.coerceAtMost(MAX_PAGE_WIDTH_PX).coerceAtMost(widest).coerceAtLeast(1)
    return width to (width * aspect).toInt().coerceAtLeast(1)
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
    private val saver = PageProgressSaver(viewModelScope, appScope, pendingWrites, SAVE_DELAY_MS) { client.saveProgress(bookId, it) }

    fun onPage(index: Int, total: Int) = saver.onPage(index, total)

    override fun onCleared() {
        val book = (state as? LoadState.Ready)?.value ?: return
        saver.close { book.document.closeSafely() }
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

package app.antresol.mobile.ui.detail

import app.antresol.core.model.BookDetail
import app.antresol.core.model.Kind
import app.antresol.core.playback.Resume

/** The main button of a book, audiobook or comic page. */
sealed interface BookAction {
    /** Listen to an audiobook from [fromMs] on the whole-book timeline; [resume] = there was progress. */
    data class Listen(val fromMs: Long, val resume: Boolean) : BookAction

    /** Open comic volume [volumeId] in the reader. */
    data class ReadComic(val volumeId: Long, val resume: Boolean, val fromStart: Boolean = false) : BookAction

    /** Open book [bookId] in the PDF reader. */
    data class ReadPdf(val bookId: Long, val resume: Boolean, val fromStart: Boolean = false) : BookAction

    /** A book in a format the app cannot open yet (EPUB, FB2…). */
    data class Unsupported(val format: String) : BookAction

    companion object {
        fun of(book: BookDetail): BookAction? = when (book.card.kind) {
            Kind.AUDIOBOOK -> {
                if (book.files.isEmpty()) {
                    null
                } else {
                    val from = Resume.audiobookStartMs(book.progress, book.durationMs)
                    Listen(from, resume = from > 0)
                }
            }
            Kind.COMIC_VOLUME -> ReadComic(book.card.id, resume = book.progress.inProgress())
            Kind.COMIC -> {
                // Continue the volume being read, else the first unfinished one, else start over.
                val volumes = book.volumes
                val reading = volumes.firstOrNull { it.progress > 0.0 && it.progress < 1.0 }
                val next = reading ?: volumes.firstOrNull { it.progress < 1.0 } ?: volumes.firstOrNull()
                next?.let { ReadComic(it.id, resume = reading != null) }
            }
            Kind.BOOK -> {
                val pdf = book.files.firstOrNull { it.format.equals("PDF", ignoreCase = true) }
                when {
                    pdf != null -> ReadPdf(book.card.id, resume = book.progress.inProgress())
                    book.files.isNotEmpty() -> Unsupported(book.files.first().format.uppercase())
                    else -> null
                }
            }
            else -> null
        }

        private fun app.antresol.core.model.BookProgress?.inProgress(): Boolean =
            this != null && !finished && (fraction > 0.0 || locator.isNotBlank())
    }
}

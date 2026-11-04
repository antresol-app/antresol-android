package app.antresol.core.reading

import app.antresol.core.model.ProgressUpdate

/**
 * Comic reading progress as the web reader stores it (spec section 8.5):
 * `locator = "page:<1-based page>"`, `fraction = (page + 1) / count`.
 */
object ComicProgress {
    private val PAGE = Regex("""^page:(\d+)$""")

    fun locator(pageIndex: Int): String = "page:${pageIndex + 1}"

    fun fraction(pageIndex: Int, count: Int): Double =
        if (count <= 0) 0.0 else ((pageIndex + 1).toDouble() / count).coerceIn(0.0, 1.0)

    fun update(pageIndex: Int, count: Int): ProgressUpdate = ProgressUpdate.reader(locator(pageIndex), fraction(pageIndex, count))

    /**
     * The 0-based page to open: the saved page, or the first page for a finished or unknown position.
     * A locator past the end (the volume shrank) lands on the last page.
     */
    fun startPage(locator: String?, finished: Boolean, count: Int): Int {
        if (finished || count <= 0 || locator.isNullOrEmpty()) return 0
        val page = PAGE.matchEntire(locator.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return (page - 1).coerceIn(0, count - 1)
    }
}

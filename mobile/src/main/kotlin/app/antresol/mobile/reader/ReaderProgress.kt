package app.antresol.mobile.reader

/**
 * Reader positions as the server stores them: `locator = "page:<1-based page>"` and
 * `fraction = (page index + 1) / count` (spec section 8.5). PDFs use the same form.
 */
object ReaderProgress {
    private val PAGE = Regex("""^page:(\d+)$""")

    fun locator(pageIndex: Int): String = "page:${pageIndex + 1}"

    fun fraction(pageIndex: Int, count: Int): Double =
        if (count <= 0) 0.0 else ((pageIndex + 1).toDouble() / count).coerceIn(0.0, 1.0)

    /**
     * The 0-based page to open: the saved page, or 0 when nothing (or something unreadable) was
     * saved or the book was finished.
     */
    fun startPage(locator: String?, finished: Boolean, count: Int): Int {
        if (finished || count <= 0) return 0
        val page = locator?.trim()?.let { PAGE.matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return (page - 1).coerceIn(0, count - 1)
    }
}

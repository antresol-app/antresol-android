package app.antresol.core.reading

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicProgressTest {
    @Test
    fun locatorAndFractionMatchTheWebReader() {
        assertEquals("page:1", ComicProgress.locator(0))
        assertEquals("page:12", ComicProgress.locator(11))
        assertEquals(0.5, ComicProgress.fraction(4, 10), 1e-9)
        assertEquals(1.0, ComicProgress.fraction(9, 10), 1e-9)
        assertEquals(0.0, ComicProgress.fraction(3, 0), 1e-9)
        val update = ComicProgress.update(2, 3)
        assertEquals("page:3", update.locator)
        assertEquals(1.0, update.fraction!!, 1e-9)
    }

    @Test
    fun startPageReadsTheSavedLocator() {
        assertEquals(4, ComicProgress.startPage("page:5", finished = false, count = 10))
        assertEquals(9, ComicProgress.startPage("page:40", finished = false, count = 10))
        assertEquals(0, ComicProgress.startPage("page:5", finished = true, count = 10))
        assertEquals(0, ComicProgress.startPage("epubcfi(/6/2)", finished = false, count = 10))
        assertEquals(0, ComicProgress.startPage(null, finished = false, count = 10))
        assertEquals(0, ComicProgress.startPage("page:0", finished = false, count = 10))
    }
}

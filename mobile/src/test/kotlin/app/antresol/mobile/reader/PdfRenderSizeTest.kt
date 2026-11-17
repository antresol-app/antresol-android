package app.antresol.mobile.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRenderSizeTest {
    @Test
    fun aPhonePageIsRenderedAtTheScreenWidth() {
        // A4 in points on a 1080 px wide phone: well under the pixel limit.
        assertEquals(1080 to 1528, pdfRenderSize(595, 842, 1080))
    }

    @Test
    fun aLargePageIsCappedByItsPixels() {
        // 2400 px wide A4 would be 8.1 MP (32 MB): cut down to 4 MP, the shape kept.
        val (width, height) = pdfRenderSize(595, 842, 2_400)
        assertTrue(width.toLong() * height <= MAX_PAGE_PIXELS)
        assertTrue(width in 1_600..1_700)
        assertEquals(842.0 / 595, height.toDouble() / width, 0.01)
    }

    private val mb = 1024L * 1024

    @Test
    fun anOrdinaryHeapRendersALargePageAtItsFullWidth() {
        // A4 at 2400 px is 2400×3396, 32.6 MB: an eighth of a 256 MB heap (33.5 MB) has room for it.
        for (heap in listOf(256, 384, 512)) assertEquals("heap $heap", 2_400 to 3_396, pdfRenderSize(595, 842, 2_400, pdfMaxPagePixels(heap * mb)))
        // Under that, as wide as an eighth of the heap allows: 2108 px at 192 MB, 1721 px at 128 MB.
        for ((heap, expected) in listOf(192 to 2_108, 128 to 1_721)) {
            val (width, height) = pdfRenderSize(595, 842, 2_400, pdfMaxPagePixels(heap * mb))
            assertEquals("heap $heap", expected, width)
            assertTrue(width.toLong() * height * 4 <= heap * mb / 8)
        }
        // Never under 4 MP.
        assertEquals(MAX_PAGE_PIXELS, pdfMaxPagePixels(64 * mb))
        assertEquals(MAX_PAGE_PIXELS, pdfMaxPagePixels(96 * mb))
    }

    @Test
    fun asManyPagesAreKeptAsMemoryAllows() {
        val a4 = 2_400L * 3_396 * 4
        // Two of the largest pages on a 256 MB heap (65 MB), three from 384 MB on.
        assertEquals(2, pdfCachedPages(256 * mb, a4))
        assertEquals(3, pdfCachedPages(384 * mb, a4))
        assertEquals(3, pdfCachedPages(512 * mb, a4))
        // A page cut to the heap's limit: two; a phone page (1080 px, 6.6 MB): three.
        assertEquals(2, pdfCachedPages(192 * mb, 2_108L * 2_983 * 4))
        assertEquals(2, pdfCachedPages(128 * mb, 1_721L * 2_435 * 4))
        assertEquals(3, pdfCachedPages(128 * mb, 1_080L * 1_528 * 4))
        // Always the page on screen, also where the 4 MP floor is more than a quarter of the heap.
        assertEquals(1, pdfCachedPages(64 * mb, 16 * mb))
        assertEquals(1, pdfCachedPages(32 * mb, 16 * mb))
        for (heap in listOf(64L, 96, 128, 192, 256, 384, 512)) {
            val page = pdfMaxPagePixels(heap * mb) * 4L
            val kept = pdfCachedPages(heap * mb, page)
            assertTrue("heap $heap", kept == 1 || kept * page <= heap * mb / 4)
        }
    }

    @Test
    fun aWidePageIsCappedByItsWidth() {
        // A landscape slide is short: the width limit applies before the pixel limit.
        assertEquals(2_400 to 1_350, pdfRenderSize(1600, 900, 5_000))
    }

    @Test
    fun brokenSizesStillGiveABitmap() {
        assertEquals(1 to 1, pdfRenderSize(0, 0, 0))
    }
}

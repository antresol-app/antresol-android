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

    @Test
    fun aLargeHeapRendersALargePageAtItsFullWidth() {
        val mb = 1024L * 1024
        // A small heap keeps the 4 MP limit; a tablet's 512 MB heap has room for A4 at 2400 px.
        assertEquals(MAX_PAGE_PIXELS, pdfMaxPagePixels(128 * mb))
        assertEquals(MAX_PAGE_PIXELS, pdfMaxPagePixels(192 * mb))
        assertEquals(2_400 to 3_396, pdfRenderSize(595, 842, 2_400, pdfMaxPagePixels(512 * mb)))
        // In between, as wide as a sixteenth of the heap allows.
        val (width, height) = pdfRenderSize(595, 842, 2_400, pdfMaxPagePixels(384 * mb))
        assertTrue(width in 2_000..2_200)
        assertTrue(width.toLong() * height * 4 <= 384 * mb / 16)
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

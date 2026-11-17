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
    fun aWidePageIsCappedByItsWidth() {
        // A landscape slide is short: the width limit applies before the pixel limit.
        assertEquals(2_400 to 1_350, pdfRenderSize(1600, 900, 5_000))
    }

    @Test
    fun brokenSizesStillGiveABitmap() {
        assertEquals(1 to 1, pdfRenderSize(0, 0, 0))
    }
}

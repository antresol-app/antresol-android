package app.antresol.core.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The modules of a QR code: [size] × [size], `true` = dark. No quiet zone (the screen draws its own). */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/**
 * QR encoding (the TV's sign-in code) and decoding (the phone's scanner) with ZXing core
 * (Apache-2.0, pure Java: no Google Play services, nothing leaves the device).
 */
object QrCodes {
    /** Encodes [text] at error correction M (a TV screen photographed at an angle still reads). */
    fun encode(text: String): QrMatrix {
        val bits = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        val size = bits.width
        return QrMatrix(size, BooleanArray(size * size) { bits.get(it % size, it / size) })
    }

    /**
     * Looks for a QR code in a camera frame's luminance plane ([luminance], [rowStride] bytes per
     * row, [width] × [height] pixels). Returns its text, or null when there is none.
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? =
        decode(PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false))

    internal fun decode(source: LuminanceSource): String? {
        val reader = QRCodeReader()
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        for (candidate in listOf(source, source.invert())) {
            try {
                return reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
            } catch (_: NotFoundException) {
            } catch (_: ReaderException) {
            } finally {
                reader.reset()
            }
        }
        return null
    }
}

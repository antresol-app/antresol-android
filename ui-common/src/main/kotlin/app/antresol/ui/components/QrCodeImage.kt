package app.antresol.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.antresol.core.pairing.QrCodes
import app.antresol.ui.theme.AntresolShapes
import kotlin.math.floor

/**
 * [text] as a QR code: black modules on a white rounded tile with a quiet zone of [size] / 12
 * (scanners need the light margin; on a dark TV screen the tile provides it). Modules snap to
 * whole pixels so the code stays crisp at any size.
 */
@Composable
fun QrCodeImage(text: String, size: Dp, contentDescription: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { QrCodes.encode(text) }
    Box(
        modifier
            .size(size)
            .background(Color.White, AntresolShapes.card)
            .padding(size / 12)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val module = floor(minOf(this.size.width, this.size.height) / matrix.size)
            val side = module * matrix.size
            val left = floor((this.size.width - side) / 2)
            val top = floor((this.size.height - side) / 2)
            for (y in 0 until matrix.size) {
                var x = 0
                while (x < matrix.size) {
                    if (!matrix[x, y]) {
                        x++
                        continue
                    }
                    // One rectangle per run of dark modules in a row.
                    val start = x
                    while (x < matrix.size && matrix[x, y]) x++
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(left + start * module, top + y * module),
                        size = Size((x - start) * module, module),
                    )
                }
            }
        }
    }
}

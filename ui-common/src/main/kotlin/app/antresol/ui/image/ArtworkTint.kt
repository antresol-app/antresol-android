package app.antresol.ui.image

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Picks the colour that tints the hero glow (the web app's artwork-coloured backdrop). */
object ArtworkTint {
    private const val SAMPLE = 24

    /**
     * A vivid, dark-enough tint from [bitmap]: the saturation-weighted average of a small sample,
     * then clamped so that white text on top stays readable.
     */
    fun from(bitmap: Bitmap): Color {
        val small = Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true)
        val hsl = FloatArray(3)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var weight = 0.0
        for (y in 0 until SAMPLE) for (x in 0 until SAMPLE) {
            val pixel = small.getPixel(x, y)
            ColorUtils.colorToHSL(pixel, hsl)
            // Favour saturated, mid-lightness pixels; greys and near-black barely count.
            val w = 0.05 + hsl[1] * (1.0 - kotlin.math.abs(hsl[2] - 0.5) * 2.0)
            r += android.graphics.Color.red(pixel) * w
            g += android.graphics.Color.green(pixel) * w
            b += android.graphics.Color.blue(pixel) * w
            weight += w
        }
        if (small !== bitmap) small.recycle()
        val average = android.graphics.Color.rgb((r / weight).toInt(), (g / weight).toInt(), (b / weight).toInt())
        ColorUtils.colorToHSL(average, hsl)
        hsl[1] = hsl[1].coerceIn(0.35f, 0.9f)
        hsl[2] = hsl[2].coerceIn(0.22f, 0.36f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
}

/**
 * The tint of the artwork at [url] (loaded through the app's Coil loader), or [fallback] while it
 * loads, when there is no artwork, or when it fails.
 */
@Composable
fun rememberArtworkTint(url: String?, fallback: Color): State<Color> {
    val context = LocalContext.current
    return produceState(initialValue = fallback, url) {
        if (url == null) {
            value = fallback
            return@produceState
        }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(96)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        value = if (bitmap != null) withContext(Dispatchers.Default) { ArtworkTint.from(bitmap) } else fallback
    }
}

package app.antresol.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Corner radii from the design reference. */
object AntresolShapes {
    val card = RoundedCornerShape(12.dp)
    val smallCard = RoundedCornerShape(10.dp)
    val control = RoundedCornerShape(12.dp)
    val sheet = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(percent = 50)
    val badge = RoundedCornerShape(6.dp)
}

/** Focus and motion tokens: focused controls scale 1.07, cards lift 1.04, ease-out `cubic-bezier(.22,1,.36,1)`. */
object AntresolMotion {
    const val FOCUS_SCALE = 1.07f
    const val CARD_FOCUS_SCALE = 1.04f
    val easeOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    const val FAST_MS = 160
    const val MEDIUM_MS = 260
    const val SLOW_MS = 600

    /** Poster aspect ratio (2:3). */
    const val POSTER_ASPECT = 2f / 3f

    /** Backdrop / episode still aspect ratio (16:9). */
    const val BACKDROP_ASPECT = 16f / 9f
}

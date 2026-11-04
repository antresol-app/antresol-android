package app.antresol.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Gradient presets: the brand tile and the profile avatars `grad-1` … `grad-8`. */
object AntresolGradients {
    /** Profile avatar presets (145°). Unknown values fall back to `grad-8`, as the spec says. */
    val avatars: Map<String, Pair<Color, Color>> = mapOf(
        "grad-1" to (Color(0xFF5E5CE6) to Color(0xFFBF5AF2)),
        "grad-2" to (Color(0xFF0A84FF) to Color(0xFF64D2FF)),
        "grad-3" to (Color(0xFFFF375F) to Color(0xFFFF9F0A)),
        "grad-4" to (Color(0xFF30D158) to Color(0xFF00C7BE)),
        "grad-5" to (Color(0xFFFF9F0A) to Color(0xFFFFD60A)),
        "grad-6" to (Color(0xFFBF5AF2) to Color(0xFFFF375F)),
        "grad-7" to (Color(0xFF00C7BE) to Color(0xFF0A84FF)),
        "grad-8" to (Color(0xFF8E8E93) to Color(0xFF48484A)),
    )

    val avatarIds: List<String> = avatars.keys.toList()

    fun avatarColors(avatar: String?): Pair<Color, Color> = avatars[avatar] ?: avatars.getValue("grad-8")

    fun avatar(avatar: String?): Brush = avatarColors(avatar).let { (from, to) -> cssLinearGradient(145f, from, to) }

    /** Brand tile / splash: linear-gradient(160deg, #3a1c8c, #9b2fc9). */
    val brand: Brush = cssLinearGradient(160f, AntresolColors.BrandStart, AntresolColors.BrandEnd)

    /** A stable gradient for a title placeholder (no poster), picked from the avatar presets. */
    fun placeholder(seed: String): Brush {
        val (from, to) = avatars.getValue(avatarIds[abs(seed.hashCode() % avatarIds.size)])
        return cssLinearGradient(160f, from.copy(alpha = 0.55f), to.copy(alpha = 0.25f))
    }

    /** Hero fade: transparent at the top to the black background at the bottom. */
    val heroFade: Brush = Brush.verticalGradient(
        0f to Color.Transparent,
        0.55f to AntresolColors.Background.copy(alpha = 0.35f),
        1f to AntresolColors.Background,
    )

    /** Left-side scrim so hero text stays readable over bright artwork. */
    val heroScrim: Brush = Brush.horizontalGradient(
        0f to AntresolColors.Background.copy(alpha = 0.85f),
        0.6f to Color.Transparent,
    )

    /**
     * A gradient with CSS `linear-gradient(<angle>deg, from, to)` semantics: the gradient line
     * passes through the centre and spans the box so that the corners get the end colours.
     */
    fun cssLinearGradient(angleDeg: Float, from: Color, to: Color): Brush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val rad = Math.toRadians(angleDeg.toDouble())
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            return LinearGradientShader(
                from = center - Offset(dx * half, dy * half),
                to = center + Offset(dx * half, dy * half),
                colors = listOf(from, to),
            )
        }

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}

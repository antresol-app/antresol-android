package app.antresol.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Colour tokens from docs/design-ref/DESIGN.md. Antresol is dark only. */
@Immutable
object AntresolColors {
    val Background = Color(0xFF000000)
    val BackgroundRaised = Color(0xFF121214)
    val BackgroundElevated = Color(0xFF1C1C1E)
    val IdentityBackground = Color(0xFF0B0B10)

    val Label = Color(0xFFF5F5F7)
    val LabelSecondary = Color(0xFFEBEBF5).copy(alpha = 0.70f)
    val LabelTertiary = Color(0xFFEBEBF5).copy(alpha = 0.45f)
    val LabelInverse = Color(0xFF000000)

    val Accent = Color(0xFF0A84FF)
    val AccentPressed = Color(0xFF0071E3)
    val Green = Color(0xFF30D158)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)

    val FillPrimary = Color(0xFF787880).copy(alpha = 0.36f)
    val FillSecondary = Color(0xFF787880).copy(alpha = 0.24f)
    val FillTertiary = Color(0xFF767680).copy(alpha = 0.16f)
    val FillQuaternary = Color(0xFF767680).copy(alpha = 0.10f)
    val Hairline = Color(0x1FFFFFFF)

    /** Glass surfaces (nav pill, secondary buttons): fill plus a faint border; blur where supported. */
    val Glass = Color(0xFF1C1C20).copy(alpha = 0.92f)
    val GlassLight = Color(0xFF282830).copy(alpha = 0.60f)
    val GlassBorder = Color(0x14FFFFFF)

    val BrandStart = Color(0xFF3A1C8C)
    val BrandEnd = Color(0xFF9B2FC9)
}

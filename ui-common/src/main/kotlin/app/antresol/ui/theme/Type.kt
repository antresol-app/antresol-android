package app.antresol.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Type scale modelled on SF Pro Display/Text as used by the web and iOS apps. Android has no SF Pro,
 * so the system sans (Roboto / Google Sans on TV) is used with matching weights and tight tracking.
 * Sizes are for phones; TV screens use [AntresolTypography.tv].
 */
@Immutable
data class AntresolTypography(
    val heroTitle: TextStyle,
    val pageTitle: TextStyle,
    val sectionTitle: TextStyle,
    val eyebrow: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodyEmphasis: TextStyle,
    val caption: TextStyle,
    val badge: TextStyle,
    val button: TextStyle,
    val avatarInitial: TextStyle,
) {
    companion object {
        private val sans = FontFamily.SansSerif

        private fun style(size: Int, weight: FontWeight, tracking: Double = 0.0, lineHeight: Double = 1.2) = TextStyle(
            fontFamily = sans,
            fontWeight = weight,
            fontSize = size.sp,
            letterSpacing = tracking.em,
            lineHeight = (size * lineHeight).sp,
            color = AntresolColors.Label,
        )

        val phone = AntresolTypography(
            heroTitle = style(34, FontWeight.Bold, -0.02, 1.08),
            pageTitle = style(30, FontWeight.Bold, -0.02, 1.1),
            sectionTitle = style(20, FontWeight.Bold, -0.01),
            eyebrow = style(12, FontWeight.SemiBold, 0.06).copy(color = AntresolColors.LabelSecondary),
            title = style(15, FontWeight.SemiBold),
            body = style(15, FontWeight.Normal, lineHeight = 1.35),
            bodyEmphasis = style(15, FontWeight.SemiBold, lineHeight = 1.35),
            caption = style(13, FontWeight.Normal).copy(color = AntresolColors.LabelSecondary),
            badge = style(11, FontWeight.SemiBold, 0.02),
            button = style(16, FontWeight.SemiBold),
            avatarInitial = style(22, FontWeight.Bold),
        )

        val tv = AntresolTypography(
            heroTitle = style(64, FontWeight.Bold, -0.025, 1.04),
            pageTitle = style(44, FontWeight.Bold, -0.02, 1.08),
            sectionTitle = style(26, FontWeight.Bold, -0.01),
            eyebrow = style(15, FontWeight.SemiBold, 0.08).copy(color = AntresolColors.LabelSecondary),
            title = style(18, FontWeight.SemiBold),
            body = style(18, FontWeight.Normal, lineHeight = 1.4),
            bodyEmphasis = style(18, FontWeight.SemiBold, lineHeight = 1.4),
            caption = style(15, FontWeight.Normal).copy(color = AntresolColors.LabelSecondary),
            badge = style(13, FontWeight.SemiBold, 0.02),
            button = style(19, FontWeight.SemiBold),
            avatarInitial = style(40, FontWeight.Bold),
        )
    }
}

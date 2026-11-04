package app.antresol.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAntresolTypography = staticCompositionLocalOf { AntresolTypography.phone }

/** Accessors for the current Antresol design tokens inside [AntresolTheme]. */
object Antresol {
    val colors: AntresolColors get() = AntresolColors
    val shapes: AntresolShapes get() = AntresolShapes
    val gradients: AntresolGradients get() = AntresolGradients

    val typography: AntresolTypography
        @Composable get() = LocalAntresolTypography.current
}

/** Material 3 colours mapped onto the Antresol palette, for stock components. */
val AntresolMaterialColors = darkColorScheme(
    primary = AntresolColors.Label,
    onPrimary = AntresolColors.LabelInverse,
    primaryContainer = AntresolColors.BackgroundElevated,
    onPrimaryContainer = AntresolColors.Label,
    secondary = AntresolColors.Accent,
    onSecondary = AntresolColors.Label,
    tertiary = AntresolColors.BrandEnd,
    background = AntresolColors.Background,
    onBackground = AntresolColors.Label,
    surface = AntresolColors.Background,
    onSurface = AntresolColors.Label,
    surfaceVariant = AntresolColors.BackgroundElevated,
    onSurfaceVariant = AntresolColors.LabelSecondary,
    surfaceContainer = AntresolColors.BackgroundRaised,
    surfaceContainerHigh = AntresolColors.BackgroundElevated,
    outline = AntresolColors.Hairline,
    outlineVariant = AntresolColors.Hairline,
    error = AntresolColors.Red,
    onError = AntresolColors.Label,
)

/**
 * The Antresol theme: pure black, white type, Apple system accents. Pass [AntresolTypography.tv]
 * on Android TV. The TV app additionally wraps its content in the tv-material theme.
 */
@Composable
fun AntresolTheme(
    typography: AntresolTypography = AntresolTypography.phone,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = AntresolMaterialColors) {
        CompositionLocalProvider(
            LocalAntresolTypography provides typography,
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = AntresolColors.Accent,
                backgroundColor = AntresolColors.Accent.copy(alpha = 0.4f),
            ),
            content = content,
        )
    }
}

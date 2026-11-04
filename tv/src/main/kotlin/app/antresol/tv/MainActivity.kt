package app.antresol.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.antresol.ui.components.BrandSplash
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolTheme
import app.antresol.ui.theme.AntresolTypography

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AntresolTvTheme {
                BrandSplash()
            }
        }
    }
}

/** The Antresol theme with TV type sizes, plus the tv-material theme for TV components. */
@Composable
fun AntresolTvTheme(content: @Composable () -> Unit) {
    AntresolTheme(typography = AntresolTypography.tv) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = AntresolColors.Label,
                onPrimary = AntresolColors.LabelInverse,
                secondary = AntresolColors.Accent,
                background = AntresolColors.Background,
                onBackground = AntresolColors.Label,
                surface = AntresolColors.BackgroundElevated,
                onSurface = AntresolColors.Label,
                surfaceVariant = AntresolColors.GlassLight,
                onSurfaceVariant = AntresolColors.LabelSecondary,
                border = AntresolColors.Accent,
                error = AntresolColors.Red,
            ),
            content = content,
        )
    }
}

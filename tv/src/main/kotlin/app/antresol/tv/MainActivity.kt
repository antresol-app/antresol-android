package app.antresol.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.antresol.tv.settings.TvSettings
import app.antresol.tv.ui.root.AntresolTvRoot
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolTheme
import app.antresol.ui.theme.AntresolTypography

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(TvSettings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AntresolTvApp
        if (savedInstanceState == null) handle(intent)
        setContent {
            AntresolTvTheme {
                AntresolTvRoot(app.session)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_NOW_PLAYING) (application as AntresolTvApp).nowPlayingRequested.value = true
    }

    companion object {
        /** Opens the now-playing screen (from the media session). */
        const val ACTION_NOW_PLAYING = "app.antresol.tv.action.NOW_PLAYING"
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

package app.antresol.tv

import android.content.Context
import app.antresol.ui.language.ProfileLocaleKeeper
import android.content.res.Resources
import android.content.res.Configuration
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.antresol.tv.autostart.AutostartController
import app.antresol.tv.autostart.AutostartLauncher
import app.antresol.tv.autostart.AutostartRequest
import app.antresol.tv.autostart.AutostartTarget
import app.antresol.tv.settings.TvSettings
import app.antresol.tv.ui.root.AntresolTvRoot
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolTheme
import app.antresol.ui.theme.AntresolTypography

class MainActivity : ComponentActivity() {
    // The profile's language survives configuration changes the activity handles (see the keeper).
    private val locale = ProfileLocaleKeeper()

    override fun attachBaseContext(newBase: Context) {
        locale.language = TvSettings(newBase).current.profileLanguage
        super.attachBaseContext(TvSettings.wrap(newBase))
    }

    override fun getResources(): Resources = locale.resources(this, super.getResources())

    override fun onConfigurationChanged(newConfig: Configuration) {
        locale.onConfigurationChanged()
        super.onConfigurationChanged(newConfig)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AntresolTvApp
        if (savedInstanceState == null) handle(intent)
        setContent {
            AntresolTvTheme {
                AntresolTvRoot(app.session, app.serverFinder, app.pairingTicket)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // Starts or stops the service that opens the app after a boot or a standby, to match the settings
        // (it also comes back after the system or an update stopped it).
        AutostartController.sync(this)
    }

    private fun handle(intent: Intent?) {
        val app = application as AntresolTvApp
        when (intent?.action) {
            ACTION_NOW_PLAYING -> app.nowPlayingRequested.value = true
            AutostartLauncher.ACTION_AUTOSTART -> app.autostartRequested.value = AutostartRequest(
                AutostartTarget.fromKey(intent.getStringExtra(AutostartLauncher.EXTRA_TARGET)),
                SystemClock.elapsedRealtime(),
            )
        }
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

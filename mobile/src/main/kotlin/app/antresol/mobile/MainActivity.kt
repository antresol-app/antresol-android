package app.antresol.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.antresol.mobile.ui.root.AntresolMobileRoot
import app.antresol.ui.theme.AntresolTheme

class MainActivity : MobileActivity() {
    private val app get() = application as AntresolMobileApp

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark only: light system bar icons on transparent bars, content draws edge to edge.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AntresolTheme {
                AntresolMobileRoot(app.session, app.serverFinder)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // The profile (its photo, name, kid flag) is read again on each return to the app, so a photo
        // changed on the web shows without a restart.
        lifecycleScope.launch { app.session.refreshUser() }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_NOW_PLAYING) app.playback.nowPlayingRequested = true
    }

    companion object {
        /** The media notification's tap target: open the app on the Now Playing screen. */
        const val ACTION_SHOW_NOW_PLAYING = "app.antresol.mobile.SHOW_NOW_PLAYING"

        /** Restarts the main screen from scratch so a new language reaches every string and server answer. */
        fun restart(activity: ComponentActivity) {
            activity.startActivity(
                Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            activity.finish()
        }
    }
}

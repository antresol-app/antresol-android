package app.antresol.mobile.player

import android.content.Context
import app.antresol.ui.language.ProfileLocaleKeeper
import android.content.res.Resources
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.antresol.mobile.AntresolMobileApp
import app.antresol.mobile.settings.AppSettings
import app.antresol.ui.theme.AntresolTheme
import app.antresol.ui.trailers.TrailerPlayerViewModel

/**
 * A trailer, full screen and landscape, in its own activity like the film player: with a `play`
 * path in the app's own player, else in the embedded player for its key. No picture-in-picture,
 * no progress, no media session: Back closes it.
 */
class TrailerPlayerActivity : ComponentActivity() {
    private val app get() = application as AntresolMobileApp

    /** The own player's model; null for an embedded trailer. */
    internal var viewModel: TrailerPlayerViewModel? = null
        private set

    // The profile's language survives configuration changes the activity handles (see the keeper).
    private val locale = ProfileLocaleKeeper()

    override fun attachBaseContext(newBase: Context) {
        val settings = AppSettings(newBase)
        locale.language = settings.profileLanguage
        super.attachBaseContext(settings.wrap(newBase))
    }

    override fun getResources(): Resources = locale.resources(this, super.getResources())

    override fun onConfigurationChanged(newConfig: Configuration) {
        locale.onConfigurationChanged()
        super.onConfigurationChanged(newConfig)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val client = app.session.client
        val play = intent.getStringExtra(EXTRA_PLAY).orEmpty()
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (client == null || (play.isBlank() && key.isBlank())) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            // A trailer takes over the screen and the speakers, like a video.
            VideoPlayerActivity.closeCurrent()
            LivePlayerActivity.closeCurrent()
            app.playback.pause()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val model = if (play.isNotBlank()) {
            ViewModelProvider(
                this,
                viewModelFactory { initializer { TrailerPlayerViewModel(applicationContext, client, play, name) } },
            )[TrailerPlayerViewModel::class.java]
        } else {
            null
        }
        viewModel = model
        setContent {
            AntresolTheme {
                if (model != null) {
                    TrailerPlayerScreen(model, onExit = ::finish)
                } else {
                    TrailerEmbedScreen(trailerKey = key, name = name, onExit = ::finish)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PLAY = "play"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_NAME = "name"

        /** The intent for a trailer: [play] when the server fetches it, else the embedded player's [key]. */
        fun intent(context: Context, play: String, key: String, name: String): Intent =
            Intent(context, TrailerPlayerActivity::class.java)
                .putExtra(EXTRA_PLAY, play)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_NAME, name)

        fun start(context: Context, play: String, key: String, name: String) {
            context.startActivity(intent(context, play, key, name))
        }
    }
}

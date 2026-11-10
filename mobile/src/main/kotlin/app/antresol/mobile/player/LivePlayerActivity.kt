package app.antresol.mobile.player

import android.content.Context
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.antresol.core.session.AuthState
import app.antresol.mobile.AntresolMobileApp
import app.antresol.mobile.settings.AppSettings
import app.antresol.ui.playback.LivePlayerViewModel
import app.antresol.ui.theme.AntresolTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A live channel, full screen and landscape, in its own activity like the video player. It closes
 * a video that is still open (picture-in-picture) and pauses music; a profile switch closes it.
 */
class LivePlayerActivity : ComponentActivity() {
    private val app get() = application as AntresolMobileApp

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppSettings(newBase).wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val client = app.session.client
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        if (client == null || channelId < 0) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            VideoPlayerActivity.closeCurrent()
            app.playback.pause()
        }
        val owner = (app.session.state.value as? AuthState.SignedIn)?.session?.let { it.serverId to it.userId }
        lifecycleScope.launch {
            app.session.state.first { state ->
                (state as? AuthState.SignedIn)?.session?.let { it.serverId to it.userId } != owner
            }
            finish()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val viewModel = ViewModelProvider(
            this,
            viewModelFactory { initializer { LivePlayerViewModel(applicationContext, client, channelId) } },
        )[LivePlayerViewModel::class.java]

        setContent {
            AntresolTheme {
                LivePlayerScreen(viewModel = viewModel, onExit = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "channelId"

        fun start(context: Context, channelId: Long) {
            context.startActivity(Intent(context, LivePlayerActivity::class.java).putExtra(EXTRA_CHANNEL_ID, channelId))
        }
    }
}

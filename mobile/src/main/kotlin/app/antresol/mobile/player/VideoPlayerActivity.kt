package app.antresol.mobile.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaSession
import app.antresol.core.session.AuthState
import app.antresol.mobile.AntresolMobileApp
import app.antresol.mobile.settings.AppSettings
import app.antresol.ui.theme.AntresolTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full-screen landscape video playback in its own activity, so it can shrink to
 * picture-in-picture while the rest of the app stays where it was. One instance at a time:
 * starting another video closes the previous player (and its server session).
 */
class VideoPlayerActivity : ComponentActivity() {
    private val app get() = application as AntresolMobileApp
    private lateinit var viewModel: VideoPlayerViewModel
    private var mediaSession: MediaSession? = null
    private var inPictureInPicture by mutableStateOf(false)

    private val canPictureInPicture: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val pipUpdater = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePictureInPictureParams()
        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePictureInPictureParams()
    }

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
        val itemId = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
        if (client == null || itemId < 0) {
            finish()
            return
        }
        // The previous player (e.g. in picture-in-picture) closes now: its media session goes first,
        // and its final write starts before this one opens a stream.
        current?.get()?.takeIf { it !== this }?.let { previous ->
            previous.closePlayer()
            previous.finish()
        }
        if (savedInstanceState == null) app.playback.pause()
        current = WeakReference(this)
        // The video belongs to the profile that started it: a profile switch or sign-out closes it.
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

        val mode = intent.getStringExtra(EXTRA_RESUME)?.let { runCatching { ResumeMode.valueOf(it) }.getOrNull() } ?: ResumeMode.Ask
        val settings = app.settings
        viewModel = ViewModelProvider(
            this,
            viewModelFactory {
                initializer {
                    VideoPlayerViewModel(
                        context = applicationContext,
                        client = client,
                        appScope = app.appScope,
                        itemId = itemId,
                        resume = mode,
                        defaultQuality = settings.videoQuality,
                        autoplayNext = { settings.autoplayNext },
                        pendingWrites = app.pendingWrites,
                    )
                }
            },
        )[VideoPlayerViewModel::class.java]
        // Headset buttons, Bluetooth and the picture-in-picture window's play/pause.
        // A unique id: the previous player's session may not be released yet.
        mediaSession = MediaSession.Builder(this, viewModel.player)
            .setId("$SESSION_ID-${sessionCounter.incrementAndGet()}")
            .build()
        viewModel.player.addListener(pipUpdater)
        updatePictureInPictureParams()

        setContent {
            AntresolTheme {
                VideoPlayerScreen(
                    viewModel = viewModel,
                    inPictureInPicture = inPictureInPicture,
                    canPictureInPicture = canPictureInPicture,
                    onPictureInPicture = ::enterPictureInPicture,
                    onExit = ::finish,
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters automatically (setAutoEnterEnabled); older versions need the call.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && ::viewModel.isInitialized && viewModel.player.isPlaying) {
            enterPictureInPicture()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onPause() {
        super.onPause()
        // Start the final progress write before the previous screen resumes and refreshes.
        if (isFinishing) closePlayer()
    }

    override fun onStop() {
        super.onStop()
        // The picture-in-picture window was closed: playback ends with it.
        if (inPictureInPicture) finish()
        if (isFinishing) closePlayer()
    }

    override fun onDestroy() {
        // Normally released in onPause; the ViewModel (and its player) may be cleared before this runs.
        releaseMediaSession()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    /** Releases the media session (never outliving the player) and starts the player's final write. */
    private fun closePlayer() {
        releaseMediaSession()
        if (::viewModel.isInitialized) viewModel.close()
    }

    private fun releaseMediaSession() {
        if (::viewModel.isInitialized) viewModel.player.removeListener(pipUpdater)
        mediaSession?.release()
        mediaSession = null
    }

    private fun enterPictureInPicture() {
        if (!canPictureInPicture || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
    }

    private fun updatePictureInPictureParams() {
        if (!canPictureInPicture || Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !::viewModel.isInitialized) return
        runCatching { setPictureInPictureParams(pictureInPictureParams()) }
    }

    private fun pictureInPictureParams(): PictureInPictureParams {
        val size = viewModel.player.videoSize
        // The system accepts ratios between 1:2.39 and 2.39:1.
        val ratio = if (size.width > 0 && size.height > 0) {
            val value = size.width * size.pixelWidthHeightRatio / size.height
            when {
                value > MAX_RATIO -> Rational(239, 100)
                value < 1 / MAX_RATIO -> Rational(100, 239)
                else -> Rational((size.width * size.pixelWidthHeightRatio).toInt(), size.height)
            }
        } else {
            Rational(16, 9)
        }
        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(viewModel.player.isPlaying)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    companion object {
        private const val EXTRA_ITEM_ID = "itemId"
        private const val EXTRA_RESUME = "resume"
        private const val SESSION_ID = "antresol-video"
        private const val MAX_RATIO = 2.39f

        private var current: WeakReference<VideoPlayerActivity>? = null
        private val sessionCounter = AtomicInteger()

        /** Plays a movie or episode; a series or season id plays its next episode. */
        fun start(context: Context, itemId: Long, resume: ResumeMode) {
            context.startActivity(
                Intent(context, VideoPlayerActivity::class.java)
                    .putExtra(EXTRA_ITEM_ID, itemId)
                    .putExtra(EXTRA_RESUME, resume.name),
            )
        }
    }
}

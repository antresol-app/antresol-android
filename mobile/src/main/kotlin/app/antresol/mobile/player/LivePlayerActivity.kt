package app.antresol.mobile.player

import android.app.PictureInPictureParams
import android.content.Context
import app.antresol.ui.language.ProfileLocaleKeeper
import android.content.res.Resources
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.OrientationEventListener
import android.content.pm.ActivityInfo
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.media3.session.MediaSession
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.session.AuthState
import app.antresol.mobile.AntresolMobileApp
import app.antresol.mobile.settings.AppSettings
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.ui.playback.LiveMediaSession
import app.antresol.ui.playback.LivePlayerViewModel
import app.antresol.ui.theme.AntresolTheme
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A live channel in its own activity like the video player: in portrait the picture on top with the
 * programme and the channels under it, in landscape full screen ([LiveFullscreen]). It closes
 * a video that is still open (picture-in-picture) and pauses music; a profile switch closes it.
 * Leaving the app while the channel plays shrinks it to picture-in-picture; closing that window
 * ends it. One at a time: opening another channel or a video closes this one.
 */
class LivePlayerActivity : ComponentActivity() {
    private val app get() = application as AntresolMobileApp
    private lateinit var viewModel: LivePlayerViewModel
    private var mediaSession: MediaSession? = null
    private var inPictureInPicture by mutableStateOf(false)

    // Full screen (LiveFullscreen): landscape on a phone, or the button on a tablet.
    private var landscape by mutableStateOf(false)
    private var smallestWidthDp by mutableStateOf(0)
    private var userFullscreen by mutableStateOf(false)

    /** The picture alone, immersive (the system bars hidden). */
    val fullscreen: Boolean
        get() = LiveFullscreen.isFullscreen(landscape, smallestWidthDp, userFullscreen, inPictureInPicture)

    /** The orientation the full-screen button locked, until the phone is held that way (null: none). */
    private var lockedLandscape: Boolean? = null
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                val target = lockedLandscape ?: return
                if (!LiveFullscreen.heldAsLocked(degrees, target)) return
                lockedLandscape = null
                disable()
                // With auto-rotate off the lock stays: the sensor would not turn the picture back.
                if (autoRotate()) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private val canPictureInPicture: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val pipUpdater = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePictureInPictureParams()
    }

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
        readConfiguration(newConfig)
    }

    private fun readConfiguration(config: Configuration) {
        landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        smallestWidthDp = config.smallestScreenWidthDp
        applySystemBars()
    }

    /** Immersive in full screen (bars come back with a swipe), the status bar over the picture otherwise. */
    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The full-screen button: turns the phone's picture, or toggles in place on a tablet. */
    fun toggleFullscreen() {
        when (val t = LiveFullscreen.toggle(fullscreen, smallestWidthDp, userFullscreen)) {
            is LiveFullscreen.Toggle.InPlace -> {
                userFullscreen = t.fullscreen
                applySystemBars()
            }
            is LiveFullscreen.Toggle.Rotate -> {
                lockedLandscape = t.landscape
                requestedOrientation = t.orientation
                if (orientationListener.canDetectOrientation()) orientationListener.enable()
            }
        }
    }

    private fun autoRotate(): Boolean =
        runCatching { Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1 }.getOrDefault(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val client = app.session.client
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        val signedIn = (app.session.state.value as? AuthState.SignedIn)?.session
        if (client == null || channelId < 0 || signedIn == null) {
            finish()
            return
        }
        current?.get()?.takeIf { it !== this }?.let { previous ->
            previous.releaseMediaSession()
            previous.finish()
        }
        current = WeakReference(this)
        if (savedInstanceState == null) {
            VideoPlayerActivity.closeCurrent()
            app.playback.pause()
        }
        val owner = signedIn.serverId to signedIn.userId
        lifecycleScope.launch {
            app.session.state.first { state ->
                (state as? AuthState.SignedIn)?.session?.let { it.serverId to it.userId } != owner
            }
            finish()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        readConfiguration(resources.configuration)

        val filter = ChannelFilter.fromKey(intent.getStringExtra(EXTRA_FILTER))
        viewModel = ViewModelProvider(
            this,
            viewModelFactory {
                initializer {
                    // «Качество» is the app's video quality setting, shared with films.
                    val settings = app.settings
                    LivePlayerViewModel(applicationContext, client, channelId, filter, quality = settings.videoQuality, onQualityChosen = settings::updateVideoQuality)
                }
            },
        )[LivePlayerViewModel::class.java]
        // Headset buttons, Bluetooth, the picture-in-picture window's play/pause; live, no seeking.
        mediaSession = LiveMediaSession.build(this, viewModel.player)
        viewModel.player.addListener(pipUpdater)
        updatePictureInPictureParams()

        setContent {
            AntresolTheme {
                // The strip's cards show the programmes' pictures.
                CompositionLocalProvider(LocalMediaUrls provides client.urls) {
                    LivePlayerScreen(
                        viewModel = viewModel,
                        inPictureInPicture = inPictureInPicture,
                        canPictureInPicture = canPictureInPicture,
                        fullscreen = fullscreen,
                        onPictureInPicture = ::enterPictureInPicture,
                        onToggleFullscreen = ::toggleFullscreen,
                        onExit = ::finish,
                    )
                }
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
        applySystemBars()
    }

    override fun onStop() {
        super.onStop()
        // The picture-in-picture window was closed: the channel ends with it.
        if (inPictureInPicture) finish()
        if (isFinishing) releaseMediaSession()
    }

    override fun onDestroy() {
        orientationListener.disable()
        releaseMediaSession()
        if (current?.get() === this) current = null
        super.onDestroy()
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

    /** Channels are 16:9 (the server encodes 1920×1080). */
    private fun pictureInPictureParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAutoEnterEnabled(viewModel.player.isPlaying)
                setSeamlessResizeEnabled(true)
            }
        }
        .build()

    companion object {
        private const val EXTRA_CHANNEL_ID = "channelId"
        private const val EXTRA_FILTER = "filter"

        private var current: WeakReference<LivePlayerActivity>? = null

        /** Closes the open channel (for example in picture-in-picture) before a video plays. */
        fun closeCurrent() {
            current?.get()?.let { previous ->
                previous.releaseMediaSession()
                previous.finish()
            }
        }

        /** Opens [channelId]; zapping stays inside the list of [filter] it was opened from. */
        fun start(context: Context, channelId: Long, filter: ChannelFilter = ChannelFilter.All) {
            context.startActivity(
                Intent(context, LivePlayerActivity::class.java)
                    .putExtra(EXTRA_CHANNEL_ID, channelId)
                    .putExtra(EXTRA_FILTER, filter.key),
            )
        }
    }
}

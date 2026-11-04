package app.antresol.tv

import android.app.Application
import android.content.Context
import android.os.Build
import app.antresol.core.api.ServerLanguage
import app.antresol.core.model.DeviceInfo
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.tv.playback.AudioEngine
import app.antresol.tv.settings.TvSettings
import app.antresol.ui.data.DataStoreSessionStore
import app.antresol.ui.image.AntresolImageLoader
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Process-wide objects: the session (API client), Coil's image loader with the Bearer header,
 * the TV settings and the audio engine that keeps music and audiobooks playing between screens.
 */
class AntresolTvApp : Application(), ImageLoaderFactory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var session: SessionManager
        private set

    lateinit var settings: TvSettings
        private set

    private val audioEngine = lazy { AudioEngine(this, { session.client }, appScope) }

    /** Music and audiobook playback, shared with [app.antresol.tv.playback.PlaybackService]. Created on first use. */
    val audio: AudioEngine by audioEngine

    /** Set when the system asks to show the now-playing screen (tap on the media session). */
    val nowPlayingRequested = MutableStateFlow(false)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(TvSettings.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        settings = TvSettings(this)
        session = SessionManager(
            store = DataStoreSessionStore(this),
            deviceInfo = { id ->
                DeviceInfo(
                    id = id,
                    name = getString(R.string.device_name, Build.MODEL),
                    model = Build.MODEL,
                    version = BuildConfig.VERSION_NAME,
                )
            },
            language = ::serverLanguage,
            scope = appScope,
        )
        // Another profile (or none) must not keep listening to what the previous one played.
        appScope.launch {
            session.state
                .map { (it as? AuthState.SignedIn)?.session?.let { active -> active.serverId to active.userId } }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (audioEngine.isInitialized()) audio.stop() }
        }
    }

    override fun newImageLoader(): ImageLoader = AntresolImageLoader.create(
        context = this,
        baseUrl = { session.client?.baseUrl },
        token = { session.client?.token },
        language = ::serverLanguage,
    )

    private fun serverLanguage(): String = ServerLanguage.from(Locale.getDefault().toLanguageTag())
}

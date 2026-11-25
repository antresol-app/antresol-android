package app.antresol.tv

import android.app.Application
import android.content.Context
import android.content.res.Resources
import app.antresol.ui.language.ProfileLocaleKeeper
import android.os.Build
import android.os.StrictMode
import android.provider.Settings
import app.antresol.core.api.ServerLanguage
import app.antresol.core.discovery.ServerFinder
import app.antresol.core.model.DeviceInfo
import app.antresol.core.pairing.PairingTicket
import app.antresol.core.playback.PendingWrites
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.tv.autostart.AppVisibility
import app.antresol.tv.autostart.AutostartRequest
import app.antresol.tv.autostart.LastChannelStore
import app.antresol.tv.autostart.PrefsLastChannelStore
import app.antresol.tv.diagnostics.Diagnostics
import app.antresol.tv.homechannels.DeepLinkRequest
import app.antresol.tv.homechannels.HomeChannels
import app.antresol.tv.playback.AudioEngine
import app.antresol.tv.settings.TvSettings
import app.antresol.ui.data.DataStoreSessionStore
import app.antresol.ui.discovery.LanDiscovery
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /**
     * Antresol servers on the home network for the server screen (UDP probe + mDNS). Also probes
     * the last server used directly when it is a private address.
     */
    var serverFinder: ServerFinder = ServerFinder.None

    /** A sign-in code the TV started when a phone set it up, on its way to the sign-in QR screen. */
    val pairingTicket = PairingTicket()

    /** Final progress writes of closed players, awaited by screens that show progress. */
    val pendingWrites = PendingWrites()

    /** What the addons of the signed-in profile offer (ways to watch, searches); asked once per sign-in. */
    val ways = app.antresol.core.ways.WaysCatalog { session.client }

    /** The way each title plays with «Play» (chosen in its menu of ways), kept on this device. */
    val wayDefaults: app.antresol.core.ways.WayDefaults by lazy { app.antresol.ui.ways.DeviceWayDefaults(this) }

    /** Set when the system asks to show the now-playing screen (tap on the media session). */
    val nowPlayingRequested = MutableStateFlow(false)

    /** Whether the app is in front for the viewer: the autostart does not open an app that already is. */
    val visibility = AppVisibility()

    /** Set when «Открывать Antresol при включении» started the app: what the shell opens (once). */
    val autostartRequested = MutableStateFlow<AutostartRequest?>(null)

    /** The live channel the viewer was watching last, for «Где остановился» after a restart ([LastChannel]). */
    val lastChannel: LastChannelStore by lazy { PrefsLastChannelStore(this) }

    /** Set when a tile of the TV home screen (Watch Next, the «Antresol» channel) or a deep link opened the app: what the shell opens (once). */
    val deepLinkRequested = MutableStateFlow<DeepLinkRequest?>(null)

    /** The log kept for the next time the box hangs (Settings → Diagnostics). */
    lateinit var diagnostics: Diagnostics
        private set

    /** Watch Next and the «Antresol» channel on the TV home screen, and the sync that keeps them current. */
    val homeChannels: HomeChannels by lazy { HomeChannels(this) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(TvSettings.wrap(base))
    }

    // Text resolved through the application context (view models, players, the media session)
    // follows the profile's current language, not the one cached when the process started.
    private val locale = ProfileLocaleKeeper()

    override fun getResources(): Resources {
        val base = super.getResources()
        if (!::settings.isInitialized) return base
        locale.language = settings.profileLanguage
        return locale.resources(this, base)
    }

    override fun onCreate() {
        super.onCreate()
        // Debug builds name the disk and network work done on the main thread (a frozen box starts with a slow main thread).
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build())
        }
        registerActivityLifecycleCallbacks(visibility)
        diagnostics = Diagnostics(this) { visibility.isVisible }.also { it.start(appScope) }
        settings = TvSettings(this)
        serverFinder = LanDiscovery(this, unicastHosts = ::lastServerHost)
        session = SessionManager(
            store = DataStoreSessionStore(this),
            deviceInfo = { id ->
                DeviceInfo(
                    id = id,
                    name = tvName(),
                    model = Build.MODEL,
                    version = BuildConfig.VERSION_NAME,
                )
            },
            language = ::serverLanguage,
            scope = appScope,
        )
        // Save the audiobook position while the token still works; sign-out revokes it next.
        session.beforeSignOut = { if (audioEngine.isInitialized()) audio.stopAndFlush() }
        // Another profile (or none) must not keep listening to what the previous one played.
        appScope.launch {
            session.state
                .map { (it as? AuthState.SignedIn)?.session?.let { active -> active.serverId to active.userId } }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (audioEngine.isInitialized()) audio.stop() }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::diagnostics.isInitialized) diagnostics.onTrimMemory(level)
    }

    override fun newImageLoader(): ImageLoader = AntresolImageLoader.create(
        context = this,
        baseUrl = { session.client?.baseUrl },
        token = { session.client?.token },
        language = ::serverLanguage,
    )

    /** The TV's own name from its settings («Гостиная»), else "Android TV (model)": the phone approving a QR sign-in shows it. */
    private fun tvName(): String =
        runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.device_name, Build.MODEL)

    /** The last server is also probed directly (when it is a private address). */
    private suspend fun lastServerHost(): List<String> = listOfNotNull(session.lastServerUrl()?.toHttpUrlOrNull()?.host)

    private fun serverLanguage(): String = ServerLanguage.from(Locale.getDefault().toLanguageTag())
}

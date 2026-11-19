package app.antresol.tv.homechannels

import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.antresol.core.session.AuthState
import app.antresol.tv.AntresolTvApp
import app.antresol.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Antresol on the TV's home screen: the Watch Next row («Продолжить») and the «Antresol» preview channel
 * («Новое в медиатеке»). This is the app-side owner of [HomeSync]. A run happens
 *
 * - when the app is opened ([onAppStart]; not more often than every [MIN_START_INTERVAL_MS]),
 * - after a player closed and its final progress write landed (`pendingWrites`),
 * - when the profile changes or signs out (the previous profile's tiles must go at once),
 * - every [HomeChannelsWork.INTERVAL_MIN] minutes by WorkManager, while someone is signed in and the network is there.
 *
 * Nothing is asked of the server and no work is scheduled while nobody is signed in: the one run that notices it
 * removes the tiles and cancels the periodic work. A device with no TV provider is noticed before anything else
 * ([HomeStore.available]): no request, no pictures, no periodic job.
 */
@OptIn(FlowPreview::class)
class HomeChannels(private val app: AntresolTvApp) {
    private val prefs = HomeChannelPrefs(app)

    val sync: HomeSync by lazy {
        HomeSync(
            source = ServerHomeSource(app.session),
            artwork = HomeArtworkStore(
                dir = HomeArtworkFiles.dir(app),
                secret = prefs.artworkSecret,
                authority = HomeArtworkFiles.authority(app),
                placeholderUri = "android.resource://${app.packageName}/drawable/tv_banner",
                fetch = ::fetchImage,
            ),
            store = TvProviderStore(
                context = app,
                prefs = prefs,
                channelName = { app.getString(R.string.home_channel_name) },
                channelDescription = { app.getString(R.string.home_channel_description) },
            ),
            linkNonce = prefs.linkNonce,
        )
    }

    /** The link in a tile's address, or null when it is not one of ours (wrong or missing nonce, see [HomeLinks]). */
    fun parseLink(uri: String?): HomeLink? = HomeLinks.parse(uri, prefs.linkNonce)

    private val requests = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var forced = false

    @Volatile private var lastSyncedAt = Long.MIN_VALUE / 2

    private var attached = false

    private val _pendingBrowsable = MutableStateFlow<Long?>(null)

    /** The channel to ask the user to let onto the home screen (once); the activity asks, because the dialog is a screen. */
    val pendingBrowsable: StateFlow<Long?> = _pendingBrowsable.asStateFlow()

    /** The activity asked; whatever the answer, the user is not asked again. */
    fun browsableAsked() {
        prefs.browsableRequested = true
        _pendingBrowsable.value = null
    }

    /** The app came to the front. */
    fun onAppStart() {
        attach()
        request(force = false)
    }

    /** Asks for a run soon; runs close together become one. [force]: even when the last run was a moment ago. */
    fun request(force: Boolean) {
        if (force) forced = true
        requests.trySend(Unit)
    }

    private fun attach() {
        if (attached) return
        attached = true
        val scope = app.appScope
        scope.launch {
            requests.receiveAsFlow().debounce(DEBOUNCE_MS).collect {
                if (forced || SystemClock.elapsedRealtime() - lastSyncedAt >= MIN_START_INTERVAL_MS) {
                    forced = false
                    runNow()
                }
            }
        }
        // A different profile, or none: the tiles of the one before must not stay. The first state is what the
        // app started with ([onAppStart] runs for it). A switched «Отдельная история» is another Continue too.
        scope.launch {
            app.session.state.map(::identity).filterNotNull().distinctUntilChanged().drop(1).collect { request(force = true) }
        }
        // A player closed: its last position was written to the server, so Continue is current now.
        scope.launch { app.pendingWrites.completions.drop(1).collect { request(force = true) } }
    }

    /** Runs the job now (also called by the periodic worker, [fromWorker]). */
    suspend fun runNow(fromWorker: Boolean = false): HomeSyncResult {
        val result = withContext(Dispatchers.IO) { sync.run() }
        when (result) {
            is HomeSyncResult.Synced -> {
                lastSyncedAt = SystemClock.elapsedRealtime()
                if (!fromWorker) HomeChannelsWork.ensurePeriodic(app)
                result.channelState?.let { channel ->
                    if (channel.browsable) {
                        prefs.browsableRequested = true
                    } else if (!prefs.browsableRequested) {
                        _pendingBrowsable.value = channel.id
                    }
                }
            }
            is HomeSyncResult.SignedOut, HomeSyncResult.Unsupported -> HomeChannelsWork.cancel(app)
            is HomeSyncResult.Failed -> Log.w(TAG, "Home screen sync failed", result.cause)
        }
        return result
    }

    private fun identity(state: AuthState): String? = when (state) {
        AuthState.Starting -> null
        // Until `/api/me` answered the device's history is not known: no identity yet (not a change).
        is AuthState.SignedIn -> state.user?.let { "${state.session.serverId}/${state.session.userId}/${it.deviceHistory?.id ?: 0}" }
        else -> ""
    }

    /** A server image, with the login of the session; null when it is not there or too big to be a poster. */
    private suspend fun fetchImage(path: String): ByteArray? {
        val client = app.session.client ?: return null
        val url = client.urls.image(path) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                client.httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val source = response.body?.source()
                    if (!response.isSuccessful || source == null) {
                        null
                    } else {
                        source.request(MAX_IMAGE_BYTES + 1)
                        if (source.buffer.size > MAX_IMAGE_BYTES) null else source.readByteArray()
                    }
                }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    companion object {
        private const val TAG = "HomeChannels"
        const val DEBOUNCE_MS = 2_000L
        const val MIN_START_INTERVAL_MS = 5 * 60_000L
        private const val MAX_IMAGE_BYTES = 12L * 1024 * 1024
    }
}

/** The periodic WorkManager job that keeps the home screen current while the app is closed. */
object HomeChannelsWork {
    const val NAME = "tv-home-channels"
    const val INTERVAL_MIN = 30L

    fun ensurePeriodic(context: android.content.Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<HomeChannelsWorker>(INTERVAL_MIN, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Log.w("HomeChannels", "Could not schedule the periodic sync", it) }
    }

    fun cancel(context: android.content.Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    }
}

class HomeChannelsWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? AntresolTvApp ?: return Result.success()
        return when (app.homeChannels.runNow(fromWorker = true)) {
            // The server or the network let this run down: try again soon a few times, then wait for the next period.
            is HomeSyncResult.Failed -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

package app.antresol.core.session

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.api.MediaUrls
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.Health
import app.antresol.core.model.Me
import app.antresol.core.model.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.IOException

/** Where the app is in the connect → pick profile → signed in flow. */
sealed interface AuthState {
    /** Reading the stored session on launch. */
    data object Starting : AuthState

    /** No server configured yet: show the server address screen. */
    data object NoServer : AuthState

    /** Connected to a server, no profile signed in: show the profile picker. */
    data class ChooseProfile(
        val serverUrl: String,
        val serverId: String,
        val notice: AuthNotice? = null,
    ) : AuthState

    /** Signed in. [user] is null until `/api/me` answered (offline launch keeps the stored session). */
    data class SignedIn(val session: StoredSession, val user: Me?) : AuthState
}

/** Why the app went back to the profile picker on its own. */
enum class AuthNotice { SessionExpired }

/** The address was valid and reachable, but the server has no admin yet (finish setup in a browser). */
class ServerNeedsSetupException : IOException("The server is not set up yet")

/** Something answered at the address, but not with Antresol's `/api/health`. */
class NotAntresolServerException(cause: Throwable? = null) : IOException("Not an Antresol server", cause)

/**
 * Owns the [AntresolClient] and the sign-in state shared by the TV and phone apps.
 *
 * - [restore] on launch: re-uses the stored token (validated with `/api/me`), else the profile picker.
 * - [connect] validates a server address with `/api/health` (and `/api/setup`).
 * - [signIn] / [resume] exchange a profile for a device token, persisted through [store].
 * - Any 401 on an authenticated call drops the token and returns to [AuthState.ChooseProfile].
 *
 * [deviceInfo] builds the `device` block for a device id (name such as "Android TV (Model)").
 */
class SessionManager(
    private val store: SessionStore,
    private val deviceInfo: (deviceId: String) -> DeviceInfo,
    private val language: () -> String,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = AntresolClient.defaultHttpClient(),
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Starting)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val mutex = Mutex()

    /**
     * Runs before [signOut] revokes the token, so the app can stop playback and send its last
     * progress write while the token still works.
     */
    var beforeSignOut: suspend () -> Unit = {}

    /** The client for the current server, or null before a server is configured. */
    @Volatile
    var client: AntresolClient? = null
        private set

    /** The client of a signed-in session. Only call from signed-in screens. */
    fun requireClient(): AntresolClient = checkNotNull(client) { "No server configured" }

    /** Loads the stored session and decides the first screen. */
    suspend fun restore() {
        val saved = store.load()
        val serverUrl = saved.serverUrl
        val serverId = saved.serverId
        if (serverUrl == null || serverId == null) {
            _state.value = AuthState.NoServer
            return
        }
        val client = clientFor(serverUrl)
        val active = saved.active
        if (active == null) {
            client.token = null
            _state.value = AuthState.ChooseProfile(serverUrl, serverId)
            return
        }
        client.token = active.token
        _state.value = AuthState.SignedIn(active, null)
        refreshUser()
    }

    /**
     * Validates [address] (bare host, host:port or URL) with `/api/health` and makes it the current
     * server. Throws [IllegalArgumentException] for a malformed address, [NotAntresolServerException],
     * [ServerNeedsSetupException], or [IOException] when the server cannot be reached.
     */
    suspend fun connect(address: String): Health {
        val baseUrl = MediaUrls.normalizeBaseUrl(address)
        val probe = AntresolClient(baseUrl, httpClient, language)
        val health = try {
            probe.health()
        } catch (e: ApiException) {
            throw NotAntresolServerException(e)
        }
        if (!health.ok || health.serverId.isBlank()) throw NotAntresolServerException()
        if (probe.setup().needsSetup) throw ServerNeedsSetupException()
        mutex.withLock {
            store.update { it.connected(baseUrl, health.serverId) }
            clientFor(baseUrl).token = null
            _state.value = AuthState.ChooseProfile(baseUrl, health.serverId)
        }
        return health
    }

    /** The last server address used, to pre-fill the address field after "Change Server". */
    suspend fun lastServerUrl(): String? = store.load().let { it.serverUrl ?: it.saved.lastOrNull()?.serverUrl }

    /** The profile picker of the current server (no auth). */
    suspend fun profiles(): List<Profile> = requireClient().profiles()

    /**
     * Signs in without asking anything when possible: re-uses a saved token of a passwordless
     * profile, or signs in a passwordless profile directly. Returns false when a password is needed.
     * Saved tokens of password profiles are never re-used, so switching into them always asks.
     */
    suspend fun resume(profile: Profile): Boolean {
        if (profile.hasPassword) return false
        val chooser = _state.value as? AuthState.ChooseProfile ?: return false
        val saved = store.load().tokenFor(chooser.serverId, profile.id)
        if (saved != null && saved.serverUrl == chooser.serverUrl) {
            val client = requireClient()
            client.token = saved.token
            val user = try {
                client.me()
            } catch (e: ApiException) {
                if (!e.isUnauthorized) throw e
                store.update { it.forgotten(saved.serverId, saved.userId) }
                null
            }
            if (user != null) {
                activate(saved.copy(userName = user.name, avatar = user.avatar), user)
                return true
            }
        }
        signIn(profile, null)
        return true
    }

    /** Exchanges [profile] and [password] for a device token. Throws [ApiException] (401 = wrong password). */
    suspend fun signIn(profile: Profile, password: String?) {
        val chooser = _state.value as? AuthState.ChooseProfile ?: error("Not choosing a profile")
        val client = requireClient()
        val response = client.signIn(profile.id, password, deviceInfo(store.deviceId()))
        val session = StoredSession(
            serverUrl = chooser.serverUrl,
            serverId = chooser.serverId,
            userId = response.user.id,
            token = response.token,
            userName = response.user.name,
            avatar = response.user.avatar,
        )
        activate(session, response.user)
    }

    /** Returns to the profile picker, keeping the token for a quick switch back. */
    suspend fun switchProfile() = mutex.withLock {
        val signedIn = _state.value as? AuthState.SignedIn ?: return@withLock
        store.update { it.switchedOut() }
        client?.token = null
        _state.value = AuthState.ChooseProfile(signedIn.session.serverUrl, signedIn.session.serverId)
    }

    /** Revokes the token on the server (best effort) and returns to the profile picker. */
    suspend fun signOut() {
        val signedIn = _state.value as? AuthState.SignedIn ?: return
        beforeSignOut()
        try {
            requireClient().logout()
        } catch (_: IOException) {
            // Offline or already revoked: forgetting the token locally is what matters.
        }
        mutex.withLock {
            store.update { it.forgotten(signedIn.session.serverId, signedIn.session.userId) }
            client?.token = null
            _state.value = AuthState.ChooseProfile(signedIn.session.serverUrl, signedIn.session.serverId)
        }
    }

    /** Leaves the current server so another address can be entered. Saved tokens stay. */
    suspend fun changeServer() = mutex.withLock {
        store.update { it.disconnected() }
        client?.token = null
        _state.value = AuthState.NoServer
    }

    /** Re-reads `/api/me` for the signed-in profile (name, avatar, kid flag). Network errors are ignored. */
    suspend fun refreshUser() {
        val signedIn = _state.value as? AuthState.SignedIn ?: return
        val user = try {
            requireClient().me()
        } catch (_: IOException) {
            return // 401 is handled by onUnauthorized; offline keeps the stored session.
        }
        val session = signedIn.session.copy(userName = user.name, avatar = user.avatar)
        mutex.withLock {
            if ((_state.value as? AuthState.SignedIn)?.session?.token != session.token) return
            store.update { it.signedIn(session) }
            _state.value = AuthState.SignedIn(session, user)
        }
    }

    private suspend fun activate(session: StoredSession, user: Me) = mutex.withLock {
        store.update { it.signedIn(session) }
        client?.token = session.token
        _state.value = AuthState.SignedIn(session, user)
    }

    private fun clientFor(baseUrl: String): AntresolClient {
        client?.takeIf { it.baseUrl == baseUrl }?.let { return it }
        lateinit var created: AntresolClient
        created = AntresolClient(
            baseUrl = baseUrl,
            httpClient = httpClient,
            language = language,
            onUnauthorized = ::onUnauthorized,
        )
        client = created
        return created
    }

    /** A 401 for the active token: forget it and show the profile picker with a notice. */
    private fun onUnauthorized(failedToken: String) {
        scope.launch {
            mutex.withLock {
                val signedIn = _state.value as? AuthState.SignedIn ?: return@withLock
                if (signedIn.session.token != failedToken) return@withLock
                store.update { it.forgotten(signedIn.session.serverId, signedIn.session.userId) }
                client?.token = null
                _state.value = AuthState.ChooseProfile(
                    signedIn.session.serverUrl,
                    signedIn.session.serverId,
                    AuthNotice.SessionExpired,
                )
            }
        }
    }
}

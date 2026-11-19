package app.antresol.core.session

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.api.MediaUrls
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.Health
import app.antresol.core.model.Me
import app.antresol.core.model.HistoryRef
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

/** A server address that answered `/api/health` as Antresol and is set up ([SessionManager.check]). */
data class CheckedServer(val baseUrl: String, val health: Health)

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
        val checked = check(address)
        use(checked)
        return checked.health
    }

    /**
     * The checks of [connect] without switching servers: normalises [address] (bare host gets
     * `http://` and port 8420), asks `/api/health` and `/api/setup`. Same exceptions as [connect].
     */
    suspend fun check(address: String): CheckedServer {
        val baseUrl = MediaUrls.normalizeBaseUrl(address)
        val probe = AntresolClient(baseUrl, httpClient, language)
        val health = try {
            probe.health()
        } catch (e: ApiException) {
            throw NotAntresolServerException(e)
        }
        if (!health.ok || health.serverId.isBlank()) throw NotAntresolServerException()
        if (probe.setup().needsSetup) throw ServerNeedsSetupException()
        return CheckedServer(baseUrl, health)
    }

    /** Makes a [check]ed server the current one, exactly like a typed address: the profile picker follows. */
    suspend fun use(server: CheckedServer) = mutex.withLock {
        store.update { it.connected(server.baseUrl, server.health.serverId) }
        clientFor(server.baseUrl).token = null
        _state.value = AuthState.ChooseProfile(server.baseUrl, server.health.serverId)
    }

    /** An unauthenticated client for [baseUrl] on this manager's HTTP client (the TV's pairing start before [use]). */
    fun anonymousClient(baseUrl: String): AntresolClient = AntresolClient(baseUrl, httpClient, language)

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
                activate(saved.copy(userName = user.name, avatar = user.avatar, photo = user.photo), user)
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
            photo = response.user.photo,
        )
        activate(session, response.user)
    }

    /** The `device` block this install sends with a sign-in (also the TV's QR sign-in request). */
    suspend fun deviceInfo(): DeviceInfo = deviceInfo(store.deviceId())

    /**
     * Finishes a QR sign-in: [token] came from `/api/auth/pair/poll` and is exactly a device token of
     * `/api/auth/token`, so it is stored and used the same way. [serverId] (the pairing answer's) must
     * be the current server's, or the token is not taken.
     */
    suspend fun signInWithPairing(token: String, user: Me, serverId: String? = null) {
        val chooser = _state.value as? AuthState.ChooseProfile ?: error("Not choosing a profile")
        require(serverId.isNullOrBlank() || serverId.equals(chooser.serverId, ignoreCase = true)) { "Token of another server" }
        activate(
            StoredSession(
                serverUrl = chooser.serverUrl,
                serverId = chooser.serverId,
                userId = user.id,
                token = token,
                userName = user.name,
                avatar = user.avatar,
                photo = user.photo,
            ),
            user,
        )
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

    /** Re-reads `/api/me` for the signed-in profile (name, avatar, photo, kid flag). Network errors are ignored. */
    suspend fun refreshUser() {
        val signedIn = _state.value as? AuthState.SignedIn ?: return
        val user = try {
            requireClient().me()
        } catch (_: IOException) {
            return // 401 is handled by onUnauthorized; offline keeps the stored session.
        }
        val session = signedIn.session.copy(userName = user.name, avatar = user.avatar, photo = user.photo)
        mutex.withLock {
            val current = (_state.value as? AuthState.SignedIn)?.session
            if (current?.token != session.token) return
            // Nothing to write when the name, avatar and photo are the stored ones (every launch).
            if (session != current) store.update { it.signedIn(session) }
            _state.value = AuthState.SignedIn(session, user)
        }
    }

    /**
     * This device writes into [history] now (the answer to `PUT /api/me/devices/current/history`): the
     * signed-in user gets it as `/api/me` would give it, so the screens that depend on it reload.
     */
    suspend fun deviceHistoryChanged(history: HistoryRef?) = mutex.withLock {
        val signedIn = _state.value as? AuthState.SignedIn ?: return@withLock
        val user = signedIn.user ?: return@withLock
        if (user.deviceHistory != history) _state.value = signedIn.copy(user = user.copy(deviceHistory = history))
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

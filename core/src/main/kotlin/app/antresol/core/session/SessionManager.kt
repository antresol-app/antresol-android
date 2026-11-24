package app.antresol.core.session

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.api.HostKind
import app.antresol.core.api.MediaUrls
import app.antresol.core.model.DeviceInfo
import app.antresol.core.model.Health
import app.antresol.core.model.Me
import app.antresol.core.model.HistoryRef
import app.antresol.core.model.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where the app is in the connect → pick profile → signed in flow. */
sealed interface AuthState {
    /** Reading the stored session on launch. */
    data object Starting : AuthState

    /** No server configured yet: show the server address screen. */
    data object NoServer : AuthState

    /**
     * Connected to a server, no profile signed in: show the profile picker.
     *
     * [switchingFrom] is set when «Switch Profile» opened the picker over a live session: that
     * session stays signed in (stored, its token on the client) until another profile signs in, and
     * [SessionManager.cancelSwitch] (Back, or picking it again) returns to it.
     */
    data class ChooseProfile(
        val serverUrl: String,
        val serverId: String,
        val notice: AuthNotice? = null,
        val switchingFrom: SignedIn? = null,
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

/** How long the `https://` try of a bare public host may take before the plain `http://` address is used. */
private const val HTTPS_PROBE_SECONDS = 8L

/**
 * Owns the [AntresolClient] and the sign-in state shared by the TV and phone apps.
 *
 * - [restore] on launch: re-uses the stored token (validated with `/api/me`), else the profile picker.
 * - [connect] validates a server address with `/api/health` (and `/api/setup`).
 * - [signIn] / [resume] exchange a profile for a device token, persisted through [store].
 * - A 401 on an authenticated call only asks `/api/me` with that token; the session ends (token
 *   dropped, back to [AuthState.ChooseProfile]) only when `/api/me` answers 401 too.
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
     * `http://` and port 8420), asks `/api/health` and `/api/setup`. A bare host outside the local
     * network is tried over `https://` first and falls back to the `http://` form when it does not
     * answer there (the password should not cross the Internet in clear text if it need not). Same
     * exceptions as [connect].
     */
    suspend fun check(address: String): CheckedServer {
        val baseUrl = MediaUrls.normalizeBaseUrl(address)
        HostKind.httpsFirst(address)?.let { secure ->
            try {
                return checkAt(secure, httpClient.newBuilder().callTimeout(HTTPS_PROBE_SECONDS, TimeUnit.SECONDS).build())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerNeedsSetupException) {
                throw e // it is an Antresol server: say so, do not look for another
            } catch (e: Exception) {
                // Nothing (or nothing of ours) over https: the plain address below.
            }
        }
        return checkAt(baseUrl, httpClient)
    }

    private suspend fun checkAt(baseUrl: String, client: OkHttpClient): CheckedServer {
        val probe = AntresolClient(baseUrl, client, language)
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

    /** Checks of a token that got a 401 now running (one per token). */
    private val checking = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** A `/api/me` check of a 401 is running (tests wait for it). */
    internal fun checkingSession(): Boolean = checking.isNotEmpty()

    /** The profile picker of the current server (no auth). */
    suspend fun profiles(): List<Profile> = requireClient().profiles()

    /**
     * Signs in without asking anything when possible: re-uses a saved token of a passwordless
     * profile, or signs in a passwordless profile directly. Returns false when a password is needed.
     * Saved tokens of password profiles are never re-used, so switching into them always asks.
     */
    suspend fun resume(profile: Profile): Boolean {
        val chooser = _state.value as? AuthState.ChooseProfile ?: return false
        // The profile «Switch Profile» was opened from: back to it, no password asked.
        if (chooser.switchingFrom?.session?.userId == profile.id) return cancelSwitch()
        if (profile.hasPassword) return false
        val saved = store.load().tokenFor(chooser.serverId, profile.id)
        if (saved != null && saved.serverUrl == chooser.serverUrl) {
            // Its own client: the session being switched from keeps its token on the shared one.
            val probe = AntresolClient(saved.serverUrl, httpClient, language, token = saved.token)
            val user = try {
                probe.me()
            } catch (e: ApiException) {
                if (!e.isUnauthorized) throw e
                store.update { it.forgotten(saved.serverId, saved.userId) }
                null
            }
            if (user != null) {
                activate(saved.copy(userName = user.name, avatar = user.avatar, photo = user.photo), user)
                retire(chooser)
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
        // A failed sign-in (wrong password) leaves the token of the session being switched from on the client.
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
        retire(chooser)
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
        retire(chooser)
    }

    /**
     * Opens the profile picker over the live session: nothing is revoked or forgotten (the stored
     * session, the client's token), so [cancelSwitch] returns to it and a restart is still signed in.
     */
    suspend fun switchProfile() = mutex.withLock {
        val signedIn = _state.value as? AuthState.SignedIn ?: return@withLock
        _state.value = AuthState.ChooseProfile(signedIn.session.serverUrl, signedIn.session.serverId, switchingFrom = signedIn)
    }

    /** Back on the picker [switchProfile] opened: the session it was opened over again. False when there is none. */
    suspend fun cancelSwitch(): Boolean = mutex.withLock {
        val from = (_state.value as? AuthState.ChooseProfile)?.switchingFrom ?: return@withLock false
        client?.token = from.session.token
        _state.value = from
        true
    }

    /**
     * After another profile signed in from [chooser]: the token of a password profile it switched
     * from is revoked with its own Bearer (it is never re-used without the password); a
     * passwordless one stays saved for a quick switch back.
     */
    private suspend fun retire(chooser: AuthState.ChooseProfile) {
        val from = chooser.switchingFrom ?: return
        val old = from.session
        if (old.userId == (_state.value as? AuthState.SignedIn)?.session?.userId) return
        if (from.user?.hasPassword != true) return
        try {
            AntresolClient(old.serverUrl, httpClient, language, token = old.token).logout()
        } catch (_: IOException) {
            // Offline or already revoked: forgetting it here is what matters.
        }
        store.update { it.forgotten(old.serverId, old.userId) }
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

    /**
     * A 401 for [failedToken] from some endpoint: ask `/api/me` with it (a client that reports
     * nothing), and only its 401 ends the session — the token is forgotten and the profile picker
     * shows a notice. Any other answer (or no network) keeps the session.
     */
    private fun onUnauthorized(failedToken: String) {
        if (!checking.add(failedToken)) return
        scope.launch {
            try {
                val session = sessionOf(_state.value)?.takeIf { it.token == failedToken } ?: return@launch
                val expired = try {
                    AntresolClient(session.serverUrl, httpClient, language, token = failedToken).me()
                    false
                } catch (e: ApiException) {
                    e.isUnauthorized
                } catch (_: IOException) {
                    false
                }
                if (expired) expire(failedToken)
            } finally {
                checking.remove(failedToken)
            }
        }
    }

    /** The session signed in now, or the one the picker was opened over. */
    private fun sessionOf(state: AuthState): StoredSession? = when (state) {
        is AuthState.SignedIn -> state.session
        is AuthState.ChooseProfile -> state.switchingFrom?.session
        else -> null
    }

    private suspend fun expire(token: String) = mutex.withLock {
        val current = _state.value
        val session = sessionOf(current)?.takeIf { it.token == token } ?: return@withLock
        store.update { it.forgotten(session.serverId, session.userId) }
        if (client?.token == token) client?.token = null
        _state.value = when (current) {
            // Already on the picker: it just can no longer go back.
            is AuthState.ChooseProfile -> current.copy(switchingFrom = null, notice = AuthNotice.SessionExpired)
            else -> AuthState.ChooseProfile(session.serverUrl, session.serverId, AuthNotice.SessionExpired)
        }
    }
}

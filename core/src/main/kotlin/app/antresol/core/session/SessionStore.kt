package app.antresol.core.session

import kotlinx.serialization.Serializable

/** A device token for one profile on one server. */
@Serializable
data class StoredSession(
    val serverUrl: String,
    val serverId: String,
    val userId: Long,
    val token: String,
    val userName: String = "",
    val avatar: String = "",
)

/**
 * Everything the apps persist about servers and sign-ins. The server keeps one token per
 * (device id, user), so a token is remembered per profile to make profile switching instant.
 */
@Serializable
data class SessionState(
    val serverUrl: String? = null,
    /** `serverId` from `/api/health` of [serverUrl]; keys the saved tokens. */
    val serverId: String? = null,
    val active: StoredSession? = null,
    val saved: List<StoredSession> = emptyList(),
) {
    fun tokenFor(serverId: String, userId: Long): StoredSession? =
        saved.firstOrNull { it.serverId == serverId && it.userId == userId }

    /** Points the app at a validated server with no profile signed in. Saved tokens are kept. */
    fun connected(serverUrl: String, serverId: String): SessionState =
        copy(serverUrl = serverUrl, serverId = serverId, active = null)

    /** Forgets the current server (the user wants to enter another address). */
    fun disconnected(): SessionState = copy(serverUrl = null, serverId = null, active = null)

    /** Makes [session] the active one and remembers it for later profile switches. */
    fun signedIn(session: StoredSession): SessionState = copy(
        serverUrl = session.serverUrl,
        serverId = session.serverId,
        active = session,
        saved = saved.filterNot { it.serverId == session.serverId && it.userId == session.userId } + session,
    )

    /** Leaves the active profile but keeps its token for a quick switch back. */
    fun switchedOut(): SessionState = copy(active = null)

    /** Forgets a token (after logout or a 401). */
    fun forgotten(serverId: String, userId: Long): SessionState = copy(
        active = active?.takeUnless { it.serverId == serverId && it.userId == userId },
        saved = saved.filterNot { it.serverId == serverId && it.userId == userId },
    )
}

/** Platform persistence for [SessionState] and the device id. Implementations must be thread-safe. */
interface SessionStore {
    /** The stable device id, generated and persisted on first use. */
    suspend fun deviceId(): String

    suspend fun load(): SessionState

    /** Atomically replaces the state with `transform(current)` and returns the new state. */
    suspend fun update(transform: (SessionState) -> SessionState): SessionState
}

/** An in-memory [SessionStore] for tests and previews. */
class InMemorySessionStore(
    initial: SessionState = SessionState(),
    private val deviceId: String = DeviceIds.generate(),
) : SessionStore {
    private val lock = Any()
    private var state = initial

    override suspend fun deviceId(): String = deviceId

    override suspend fun load(): SessionState = synchronized(lock) { state }

    override suspend fun update(transform: (SessionState) -> SessionState): SessionState =
        synchronized(lock) { transform(state).also { state = it } }
}

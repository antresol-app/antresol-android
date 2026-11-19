package app.antresol.core.model

import kotlinx.serialization.Serializable

/** `GET /api/health` (no auth). */
@Serializable
data class Health(
    val ok: Boolean = false,
    val serverId: String = "",
    val version: String = "",
)

/** `GET /api/setup` (no auth). */
@Serializable
data class SetupStatus(
    val needsSetup: Boolean = false,
    val remote: Boolean = false,
)

/** One entry of the profile picker, `GET /api/profiles` (no auth). */
@Serializable
data class Profile(
    val id: Long,
    val name: String = "",
    val avatar: String = "",
    /** The profile's photo, `/api/profiles/{id}/photo?v=…` (no auth; the version changes with each upload), or "" for the letter. */
    val photo: String = "",
    val hasPassword: Boolean = false,
)

/** The signed-in user, `GET /api/me` and `user` of the token response. */
@Serializable
data class Me(
    val id: Long,
    val name: String = "",
    val avatar: String = "",
    /** As [Profile.photo]. */
    val photo: String = "",
    val hasPassword: Boolean = false,
    val isAdmin: Boolean = false,
    val isKid: Boolean = false,
    val maxRating: String? = null,
    val remote: Boolean = false,
    val libraryOnly: Boolean = true,
    /**
     * The separate history this device writes into («Отдельная история на этом устройстве»), null for
     * the profile's own. The person stays the one signed in; only progress, watched marks, «Продолжить»
     * and the family view go to the history (a managed account of the person's).
     */
    val deviceHistory: HistoryRef? = null,
)

/** A device's separate history: the managed account it writes into. */
@Serializable
data class HistoryRef(val id: Long, val name: String = "")

/** The device block sent to `POST /api/auth/token`. `id` must match [app.antresol.core.session.DeviceIds.PATTERN]. */
@Serializable
data class DeviceInfo(
    val id: String,
    val name: String = "",
    val model: String = "",
    val version: String = "",
)

@Serializable
data class TokenRequest(
    val userId: Long,
    val password: String? = null,
    val device: DeviceInfo,
)

@Serializable
data class TokenResponse(
    val token: String,
    val user: Me,
)

/** `POST /api/auth/media-key`: a 12 h signed key for `?mk=` on stream/HLS/audio/track URLs. */
@Serializable
data class MediaKey(
    val key: String,
    val expiresAt: String,
)

/** One native device session, from `GET /api/me/devices`. `id` is the session row id, not the device id. */
@Serializable
data class DeviceSession(
    val id: Long,
    val name: String = "",
    val model: String = "",
    val version: String = "",
    val createdAt: String = "",
    val lastSeen: String = "",
    val current: Boolean = false,
    /** Its separate history (null: the profile's own). */
    val history: HistoryRef? = null,
)

@Serializable
data class DeviceSessions(val items: List<DeviceSession> = emptyList())

/** Admin user listing entry, `GET /api/users`. */
@Serializable
data class UserAccount(
    val id: Long,
    val name: String = "",
    val avatar: String = "",
    val hasPassword: Boolean = false,
    val isAdmin: Boolean = false,
    val isKid: Boolean = false,
    val maxRating: String? = null,
)

/** One of the person's separate histories, `GET /api/me/managed`; [devices] are those writing into it now. */
@Serializable
data class ManagedAccount(
    val id: Long,
    val name: String = "",
    val avatar: String = "",
    val devices: List<ManagedDevice> = emptyList(),
)

@Serializable
data class ManagedDevice(val id: Long, val kind: String = "", val name: String = "", val model: String = "")

@Serializable
data class ManagedAccounts(val items: List<ManagedAccount> = emptyList())

@Serializable
data class ManagedCreateRequest(val name: String)

/**
 * `PUT /api/me/devices/current/history`: on ([separate]) into [accountId] (0: the account the device had,
 * else a new one named after it), or off, [merge] moving what it watched into the profile.
 */
@Serializable
data class DeviceHistoryRequest(val separate: Boolean, val accountId: Long = 0, val merge: Boolean = false)

/** The answer to [DeviceHistoryRequest]: the session and the history it writes into now. */
@Serializable
data class DeviceHistoryResult(val id: Long = 0, val history: HistoryRef? = null)

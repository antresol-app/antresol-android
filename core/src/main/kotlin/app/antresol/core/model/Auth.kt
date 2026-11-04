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
    val hasPassword: Boolean = false,
)

/** The signed-in user, `GET /api/me` and `user` of the token response. */
@Serializable
data class Me(
    val id: Long,
    val name: String = "",
    val avatar: String = "",
    val hasPassword: Boolean = false,
    val isAdmin: Boolean = false,
    val isKid: Boolean = false,
    val maxRating: String? = null,
    val remote: Boolean = false,
    val libraryOnly: Boolean = true,
)

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

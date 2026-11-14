package app.antresol.core.model

import kotlinx.serialization.Serializable

/** `POST /api/auth/pair/start` (TV, no auth). */
@Serializable
data class PairStartRequest(val device: DeviceInfo)

/** The answer of `start`: show [url] as the QR and [code] in large text; keep [secret] in memory only. */
@Serializable
data class PairStart(
    val code: String,
    val secret: String,
    val expiresAt: String = "",
    val interval: Int = 2,
    val url: String,
    val serverId: String = "",
)

@Serializable
data class PairPollRequest(val secret: String)

/** The body of a poll answer (200 pending/approved, 410 denied/expired). */
@Serializable
data class PairPollBody(
    val status: String = "",
    val token: String? = null,
    val user: Me? = null,
    val expiresAt: String? = null,
    val interval: Int? = null,
    val error: String? = null,
)

/** What the TV learns from one poll. */
sealed interface PairPoll {
    data class Pending(val intervalSec: Int?) : PairPoll

    /** Signed in: [token] is a normal native device token (as from `/api/auth/token`), handed out once. */
    data class Approved(val token: String, val user: Me) : PairPoll

    data class Denied(val message: String?) : PairPoll

    /** Expired, already picked up or unknown: start again. */
    data class Expired(val message: String?) : PairPoll

    /** Too many unknown secrets from this address: wait [retryAfterSec]. */
    data class Throttled(val retryAfterSec: Long) : PairPoll
}

/** `POST /api/auth/pair/lookup` / `deny` request. */
@Serializable
data class PairCodeRequest(val code: String)

/** `POST /api/auth/pair/approve`. [userId] 0 = the caller's own profile; another id only for admins. */
@Serializable
data class PairApproveRequest(val code: String, val userId: Long = 0)

@Serializable
data class PairApproved(val ok: Boolean = false, val userId: Long = 0)

/** The device that asks to sign in, as the approving phone shows it. */
@Serializable
data class PairDevice(
    val name: String = "",
    val model: String = "",
    val version: String = "",
)

/** `POST /api/auth/pair/lookup` (signed in). [remote]: the TV asked from outside the home network. */
@Serializable
data class PairLookup(
    val code: String,
    val device: PairDevice = PairDevice(),
    val remote: Boolean = false,
    val createdAt: String = "",
    val expiresAt: String = "",
)

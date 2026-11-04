package app.antresol.core.api

import java.io.IOException

/**
 * A non-2xx answer from the server. [message] is the server's localized `error` text
 * (fall back to the HTTP status when the body was not JSON), [code] the optional machine code.
 */
class ApiException(
    val status: Int,
    message: String,
    val code: String? = null,
    val retryAfterSec: Long? = null,
) : IOException(message) {
    val isUnauthorized: Boolean get() = status == 401
    val isForbidden: Boolean get() = status == 403
    val isNotFound: Boolean get() = status == 404

    /** The profile already holds the maximum number of HLS sessions. */
    val isTooManySessions: Boolean get() = status == 429

    val isRemotePasswordless: Boolean get() = code == CODE_REMOTE_PASSWORDLESS

    companion object {
        const val CODE_REMOTE_PASSWORDLESS = "remote-passwordless"
    }
}

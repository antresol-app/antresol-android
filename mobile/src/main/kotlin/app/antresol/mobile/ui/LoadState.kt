package app.antresol.mobile.ui

import android.content.res.Resources
import app.antresol.core.api.ApiException
import app.antresol.core.api.InvalidServerAddressException
import app.antresol.core.session.ServerNeedsSetupException
import app.antresol.mobile.R as MobileR
import app.antresol.ui.R
import app.antresol.ui.playback.StreamLimitException
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** The state of a screen that loads one thing from the server. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Failed(val error: Throwable) : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
}

/** Runs [block] and wraps the outcome; cancellation is rethrown. */
suspend fun <T> load(block: suspend () -> T): LoadState<T> = try {
    LoadState.Ready(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    LoadState.Failed(e)
}

/**
 * A sentence for the user. Server errors are already localized by `Accept-Language`. A 429 means
 * the stream limit only from the player ([StreamLimitException]); elsewhere (sign-in) it is throttling.
 */
fun Throwable.userMessage(resources: Resources): String = when (this) {
    is ServerNeedsSetupException -> resources.getString(R.string.server_needs_setup)
    is StreamLimitException -> resources.getString(R.string.error_too_many_streams)
    is ApiException -> when {
        status == 200 -> resources.getString(R.string.error_generic)
        // No localized `error` from the server (the client falls back to "HTTP 429").
        isTooManySessions && message.orEmpty().startsWith("HTTP ") ->
            resources.getString(MobileR.string.error_too_many_attempts)
        else -> message ?: resources.getString(R.string.error_generic)
    }
    is InvalidServerAddressException -> resources.getString(R.string.server_invalid_address)
    is IOException -> resources.getString(R.string.error_network)
    else -> resources.getString(R.string.error_generic)
}

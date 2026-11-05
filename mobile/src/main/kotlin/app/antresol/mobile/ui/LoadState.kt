package app.antresol.mobile.ui

import android.content.res.Resources
import app.antresol.core.api.ApiException
import app.antresol.core.api.InvalidServerAddressException
import app.antresol.core.session.ServerNeedsSetupException
import app.antresol.ui.R
import kotlinx.coroutines.CancellationException
import java.io.IOException

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

/** A sentence for the user. Server errors are already localized by `Accept-Language`. */
fun Throwable.userMessage(resources: Resources): String = when (this) {
    is ServerNeedsSetupException -> resources.getString(R.string.server_needs_setup)
    is ApiException -> when {
        isTooManySessions -> resources.getString(R.string.error_too_many_streams)
        status == 200 -> resources.getString(R.string.error_generic)
        else -> message ?: resources.getString(R.string.error_generic)
    }
    is InvalidServerAddressException -> resources.getString(R.string.server_invalid_address)
    is IOException -> resources.getString(R.string.error_network)
    else -> resources.getString(R.string.error_generic)
}

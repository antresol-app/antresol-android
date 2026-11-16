package app.antresol.ui.onboarding

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.antresol.core.api.ApiException
import app.antresol.core.session.NotAntresolServerException
import app.antresol.core.session.ServerNeedsSetupException
import app.antresol.core.session.SessionManager
import app.antresol.ui.R
import app.antresol.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Connecting to a server from an onboarding screen (a typed address, a server found on the
 * network): one attempt at a time, and the sentence to show when it fails. On success the session
 * moves on to the profile picker and the screen goes away.
 */
@Stable
class ServerConnectState(
    private val session: SessionManager,
    private val scope: CoroutineScope,
    private val resources: Resources,
) {
    var busy: Boolean by mutableStateOf(false)
        private set

    /** Why the last attempt failed; the screen clears it when the address is edited. */
    var error: String? by mutableStateOf(null)

    fun connect(address: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                session.connect(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.connectMessage(resources)
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun rememberServerConnectState(session: SessionManager): ServerConnectState {
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    return remember(session, resources) { ServerConnectState(session, scope, resources) }
}

/**
 * Why connecting to a server failed. An HTTP error is the server's own answer and is shown as such:
 * [ApiException] is an [IOException] too, and must not read as "can't reach the server".
 */
fun Throwable.connectMessage(resources: Resources): String = when (this) {
    is NotAntresolServerException -> resources.getString(R.string.server_not_antresol)
    is ServerNeedsSetupException, is ApiException -> userMessage(resources)
    is IOException -> resources.getString(R.string.server_unreachable)
    else -> userMessage(resources)
}

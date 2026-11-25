package app.antresol.ui.requests

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.antresol.core.api.ApiException
import app.antresol.core.model.KidAsk
import app.antresol.core.model.KidRequest
import app.antresol.core.model.KidRequestState
import app.antresol.core.requests.RequestsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * «Попросить у родителя» on a title's page: the kid's request of the title as the page was loaded, what asking
 * did to it, and why asking failed. The page's own view model owns one.
 */
class TitleRequest(
    private val api: RequestsApi,
    private val scope: CoroutineScope,
    /** Called with the request the server made (a search page patches its results with it). */
    private val onAsked: (KidRequest) -> Unit = {},
) {
    /** The title's request state as the server sent it with the page; null for an adult profile or a server without requests. */
    var state: KidRequestState? by mutableStateOf(null)
        private set

    /** The request is on its way to the server. */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** Asking failed: the server's words when it gave them (a title above the age limit), else null with [failed] set. */
    var blocked: String? by mutableStateOf(null)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set

    /** The page (or the sheet of a result) was loaded: [state] is what the server said. */
    fun load(state: KidRequestState?) {
        this.state = state
        blocked = null
        failed = false
    }

    /** The kid's request of the title, if any. */
    val request: KidRequest? get() = state?.request

    /** Asking is offered: there is no request yet, the title may be asked for and the server's answer is not awaited. */
    val canAsk: Boolean get() = state != null && state?.request == null && state?.allowed == true && blocked == null

    /** Why asking is not offered, in the server's words (a title above the profile's limit), else null. */
    val why: String? get() = blocked ?: state?.takeIf { !it.allowed }?.blocked

    fun ask(ask: KidAsk) {
        val current = state ?: return
        if (busy || current.request != null) return
        busy = true
        failed = false
        scope.launch {
            try {
                val made = api.ask(ask)
                state = current.copy(request = made)
                onAsked(made)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // 403 with code «rating»: a title above the age limit, with the reason in the profile's language.
                if (e.code == "rating") blocked = e.message else failed = true
            } catch (e: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
}

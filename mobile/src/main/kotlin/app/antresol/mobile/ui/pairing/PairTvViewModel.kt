package app.antresol.mobile.ui.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.PairApproved
import app.antresol.core.model.PairLookup
import app.antresol.core.model.Profile
import app.antresol.core.pairing.PairLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The phone calls of approving a TV (contract §2.4), so the screen can be tested without a server. */
interface PairApprover {
    suspend fun lookup(code: String): PairLookup

    suspend fun approve(code: String, userId: Long): PairApproved

    suspend fun deny(code: String)

    /** The profile picker (admins may sign the TV in to another profile). */
    suspend fun profiles(): List<Profile>
}

fun AntresolClient.pairApprover(): PairApprover = object : PairApprover {
    override suspend fun lookup(code: String): PairLookup = pairLookup(code)

    override suspend fun approve(code: String, userId: Long): PairApproved = pairApprove(code, userId)

    override suspend fun deny(code: String) = pairDeny(code)

    override suspend fun profiles(): List<Profile> = this@pairApprover.profiles()
}

/** Where «Sign in a TV» is. */
sealed interface PairStep {
    /** The scanner and the code field. [notice]: why the last scan or code was not taken. */
    data class Scan(val notice: PairNotice? = null) : PairStep

    data object Checking : PairStep

    /**
     * The TV asks to sign in: [profiles] is non-empty only for admins (they may pick another
     * profile); [selectedUserId] the profile the TV gets. [error]: the last approve/deny failure.
     */
    data class Confirm(
        val lookup: PairLookup,
        val profiles: List<Profile>,
        val selectedUserId: Long,
        val sending: Boolean = false,
        val error: Throwable? = null,
    ) : PairStep

    /** Approved ([approved]) or declined: nothing more to do, the TV picks it up. */
    data class Done(val approved: Boolean) : PairStep
}

sealed interface PairNotice {
    data object NotATvCode : PairNotice
    data object InvalidCode : PairNotice
    data object OtherServer : PairNotice
    data class Failed(val error: Throwable) : PairNotice
}

/**
 * «Sign in a TV»: a scanned QR (or a typed code) → `lookup` → show the device → Approve (as this
 * profile, or for admins another one) / Decline. The phone always calls its own signed-in server
 * ([ownServerId]); a QR naming another server is refused with a message.
 */
class PairTvViewModel(
    private val api: PairApprover,
    private val ownServerId: String,
    private val ownUserId: Long,
    private val isAdmin: Boolean,
    initialLink: String? = null,
) : ViewModel() {
    var step: PairStep by mutableStateOf(PairStep.Scan())
        private set

    private var lastRejectedScan: String? = null

    init {
        initialLink?.let(::onScanned)
    }

    /** A QR the camera read. Repeats of a rejected one are ignored (the camera keeps reading it). */
    fun onScanned(text: String) {
        if (step !is PairStep.Scan || text == lastRejectedScan) return
        val link = PairLink.parse(text)
        when {
            link == null -> reject(text, PairNotice.NotATvCode)
            !link.belongsTo(ownServerId) -> reject(text, PairNotice.OtherServer)
            else -> lookup(link.code)
        }
    }

    /** The code typed by hand. */
    fun onCodeEntered(text: String) {
        if (step !is PairStep.Scan) return
        val link = PairLink.parse(text)
        if (link == null) step = PairStep.Scan(PairNotice.InvalidCode) else lookup(link.code)
    }

    fun select(userId: Long) {
        val confirm = step as? PairStep.Confirm ?: return
        step = confirm.copy(selectedUserId = userId, error = null)
    }

    fun approve() = send { confirm ->
        // 0 = "the caller's own profile" in the contract; another id only for admins.
        val target = if (confirm.selectedUserId == ownUserId) 0 else confirm.selectedUserId
        api.approve(confirm.lookup.code, target)
        PairStep.Done(approved = true)
    }

    fun deny() = send { confirm ->
        api.deny(confirm.lookup.code)
        PairStep.Done(approved = false)
    }

    /** Back to the scanner for another TV. */
    fun again() {
        lastRejectedScan = null
        step = PairStep.Scan()
    }

    private fun reject(text: String, notice: PairNotice) {
        lastRejectedScan = text
        step = PairStep.Scan(notice)
    }

    private fun lookup(code: String) {
        step = PairStep.Checking
        viewModelScope.launch {
            step = try {
                val lookup = api.lookup(code)
                val profiles = if (isAdmin) runCatching { api.profiles() }.getOrDefault(emptyList()) else emptyList()
                PairStep.Confirm(lookup, profiles, ownUserId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PairStep.Scan(PairNotice.Failed(e))
            }
        }
    }

    private fun send(call: suspend (PairStep.Confirm) -> PairStep) {
        val confirm = step as? PairStep.Confirm ?: return
        if (confirm.sending) return
        step = confirm.copy(sending = true, error = null)
        viewModelScope.launch {
            step = try {
                call(confirm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                confirm.copy(sending = false, error = e)
            }
        }
    }
}

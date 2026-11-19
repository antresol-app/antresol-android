package app.antresol.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.DeviceHistoryRequest
import app.antresol.core.model.DeviceHistoryResult
import app.antresol.core.model.HistoryRef
import app.antresol.core.model.ManagedAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The server calls of «Отдельная история на этом устройстве»; [AntresolClient.deviceHistoryApi] is the real one. */
interface DeviceHistoryApi {
    suspend fun accounts(): List<ManagedAccount>

    suspend fun create(name: String): ManagedAccount

    suspend fun switch(request: DeviceHistoryRequest): DeviceHistoryResult
}

fun AntresolClient.deviceHistoryApi(): DeviceHistoryApi = object : DeviceHistoryApi {
    override suspend fun accounts() = managedAccounts().items

    override suspend fun create(name: String) = createManagedAccount(name)

    override suspend fun switch(request: DeviceHistoryRequest) = setDeviceHistory(request)
}

/**
 * «Отдельная история на этом устройстве», as the web's «Устройства» has it for the current device: the
 * person stays signed in, the device writes its progress, watched marks, «Продолжить» and the family
 * view into one of the person's separate histories (managed accounts). [use] switches to an existing
 * one, [create] makes one by name and switches to it, [turnOff] goes back to the profile's own history,
 * moving what was watched here into it ([turnOff] `merge = true`, «Перенести в мой профиль») or keeping it
 * apart («Оставить»: it comes back when switched on again).
 *
 * The current state is the session's (`/api/me` → `deviceHistory`), not this model's: [onSwitched] gets
 * the server's answer and the app puts it into the session, which reloads what depends on the history
 * (Home, Continue Watching). Kids never get here: the server refuses their writes (403), the apps hide
 * the setting.
 */
class DeviceHistoryModel(
    private val api: DeviceHistoryApi,
    private val onSwitched: suspend (HistoryRef?) -> Unit,
) : ViewModel() {
    /** The person's separate histories, null until read (and when reading failed: the picker still offers a new one). */
    var accounts: List<ManagedAccount>? by mutableStateOf(null)
        private set

    var busy: Boolean by mutableStateOf(false)
        private set

    /** Why the last switch failed; cleared by the next one. */
    var error: Throwable? by mutableStateOf(null)
        private set

    fun load() {
        viewModelScope.launch { reread() }
    }

    private suspend fun reread() {
        accounts = try {
            api.accounts()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            accounts
        }
    }

    fun use(accountId: Long) = run { api.switch(DeviceHistoryRequest(separate = true, accountId = accountId)) }

    /** A new history named [name] (trimmed; blank does nothing), then this device writes into it. */
    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        run {
            val account = api.create(trimmed)
            api.switch(DeviceHistoryRequest(separate = true, accountId = account.id))
        }
    }

    fun turnOff(merge: Boolean) = run { api.switch(DeviceHistoryRequest(separate = false, merge = merge)) }

    fun clearError() {
        error = null
    }

    private fun run(block: suspend () -> DeviceHistoryResult) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val result = block()
                // A merge deletes the history it moved; a new one is in the list now. Read before the
                // session hears of it: the app may then start its screens again, this model with them.
                reread()
                onSwitched(result.history)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                busy = false
            }
        }
    }
}

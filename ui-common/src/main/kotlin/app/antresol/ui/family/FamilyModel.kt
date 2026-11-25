package app.antresol.ui.family

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.antresol.core.family.Family
import app.antresol.core.family.FamilyApi
import app.antresol.core.model.FamilyMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * «Семья» of one page: where the other profiles are in the item ([members], most recent first; empty while loading, on
 * an error, for a kid and when nobody else has progress, so a page shows nothing then), and «continue from their
 * place» ([jump]). A page's own view model owns one.
 */
class FamilyModel(
    private val api: FamilyApi,
    private val scope: CoroutineScope,
) {
    var members: List<FamilyMember> by mutableStateOf(emptyList())
        private set

    /** The profile whose place is being taken (the server copies it), or null. */
    var busy: Long? by mutableStateOf(null)
        private set

    /** The last [jump] failed (the server refused the copy, or the network); cleared by the next one. */
    var failed: Boolean by mutableStateOf(false)
        private set

    private var itemId = 0L
    private var reading: Job? = null

    /** Reads the family of [itemId]; what was shown before goes at once when it is another item. */
    fun load(itemId: Long) {
        if (itemId != this.itemId) members = emptyList()
        this.itemId = itemId
        read()
    }

    /** Reads again (back from the player); what is shown stays until the answer arrives. */
    fun refresh() {
        if (itemId > 0) read()
    }

    private fun read() {
        reading?.cancel()
        val id = itemId
        if (id <= 0) return
        reading = scope.launch {
            // The page works without it: a failure shows nobody.
            members = try {
                api.members(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Continues from [m]'s place: when that is a position the server first copies it into this profile's own progress (and
     * never writes theirs), then [open] starts the player or reader there. A second call while one is on its way is ignored.
     */
    fun jump(m: FamilyMember, open: (FamilyMember) -> Unit) {
        if (busy != null) return
        busy = m.userId
        failed = false
        scope.launch {
            try {
                if (Family.adopts(m)) api.adopt(m.itemId, m.userId)
                open(m)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                // What the family says may be out of date now (they went on, or finished): ask again.
                read()
            } finally {
                busy = null
            }
        }
    }
}

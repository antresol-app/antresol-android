package app.antresol.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * Focus requesters by key (a card, a programme of the guide), kept only while an element on screen
 * uses them: a long grid or a guide paged hours ahead does not leave one behind for everything that
 * was ever scrolled past. A requester asked for before its element is composed (focus restoration)
 * is the one the element then gets.
 */
class FocusRequesters<K : Any> {
    private class Entry(val requester: FocusRequester) {
        var users = 0
    }

    private val entries = mutableMapOf<K, Entry>()

    /** How many requesters are kept. */
    val size: Int get() = entries.size

    operator fun get(key: K): FocusRequester = entries.getOrPut(key) { Entry(FocusRequester()) }.requester

    internal fun attached(key: K, requester: FocusRequester) {
        val entry = entries[key]?.takeIf { it.requester === requester || it.users > 0 }
            // Dropped since the element was composed (or replaced by one nobody uses): this one is in use.
            ?: Entry(requester).also { entries[key] = it }
        if (entry.requester === requester) entry.users++
    }

    internal fun detached(key: K, requester: FocusRequester) {
        val entry = entries[key]?.takeIf { it.requester === requester } ?: return
        if (--entry.users <= 0) entries.remove(key)
    }
}

/** `focusRequester(requesters[key])`, with the requester dropped when this element leaves the screen. */
fun <K : Any> Modifier.focusRequester(requesters: FocusRequesters<K>, key: K): Modifier {
    val requester = requesters[key]
    return focusRequester(requester).then(RequesterUseElement(requesters, key, requester))
}

private data class RequesterUseElement<K : Any>(
    val requesters: FocusRequesters<K>,
    val key: K,
    val requester: FocusRequester,
) : ModifierNodeElement<RequesterUseNode<K>>() {
    override fun create() = RequesterUseNode(requesters, key, requester)

    override fun update(node: RequesterUseNode<K>) = node.use(requesters, key, requester)

    override fun InspectorInfo.inspectableProperties() {
        name = "focusRequesterUse"
    }
}

private class RequesterUseNode<K : Any>(
    private var requesters: FocusRequesters<K>,
    private var key: K,
    private var requester: FocusRequester,
) : Modifier.Node() {
    override fun onAttach() = requesters.attached(key, requester)

    override fun onDetach() = requesters.detached(key, requester)

    fun use(requesters: FocusRequesters<K>, key: K, requester: FocusRequester) {
        if (isAttached) this.requesters.detached(this.key, this.requester)
        this.requesters = requesters
        this.key = key
        this.requester = requester
        if (isAttached) requesters.attached(key, requester)
    }
}

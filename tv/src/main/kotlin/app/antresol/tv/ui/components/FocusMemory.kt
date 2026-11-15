package app.antresol.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Remembers which element of a screen had focus (by a stable string key) so that focus comes back
 * to it after navigating away and back. Survives the back stack through [rememberSaveable].
 */
@Stable
class FocusMemory(initialKey: String?) {
    var focusedKey: String? by mutableStateOf(initialKey)
        private set

    private val requesters = mutableMapOf<String, FocusRequester>()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun onFocused(key: String) {
        focusedKey = key
    }

    /** Makes [key] the element to come back to when nothing was focused on this screen yet. */
    fun prefer(key: String) {
        if (focusedKey == null) focusedKey = key
    }

    /**
     * Focuses the remembered element, or [fallbackKey] when there is none or it is gone. [allowed]
     * is asked before every attempt (a page that started leaving must not take focus).
     */
    suspend fun restore(fallbackKey: String, allowed: () -> Boolean = { true }): Boolean {
        val key = focusedKey
        if (key != null && key != fallbackKey && requester(key).tryRequestFocus(allowed = allowed)) return true
        return requester(fallbackKey).tryRequestFocus(allowed = allowed)
    }

    /** Focuses the remembered element right now, if there is one and it is composed. */
    fun focusRemembered(): Boolean {
        val key = focusedKey ?: return false
        return try {
            requester(key).requestFocus()
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    companion object {
        val Saver: Saver<FocusMemory, String> = Saver(
            save = { it.focusedKey ?: "" },
            restore = { FocusMemory(it.ifEmpty { null }) },
        )
    }
}

/**
 * The focus memories of the page being composed. The shell provides one set per page and, when
 * the user enters the page from the navigation, focuses the element the page remembers.
 */
val LocalFocusMemories = staticCompositionLocalOf<MutableSet<FocusMemory>?> { null }

@Composable
fun rememberFocusMemory(): FocusMemory {
    val memory = rememberSaveable(saver = FocusMemory.Saver) { FocusMemory(null) }
    val pageMemories = LocalFocusMemories.current
    DisposableEffect(memory, pageMemories) {
        pageMemories?.add(memory)
        onDispose { pageMemories?.remove(memory) }
    }
    return memory
}

/** Registers this focusable under [key] in [memory]. Put it before the focusable modifier. */
fun Modifier.remembersFocus(memory: FocusMemory, key: String): Modifier =
    focusRequester(memory.requester(key)).onFocusChanged { if (it.isFocused) memory.onFocused(key) }

/** Like [remembersFocus] for a focus group (sort pills): remembered while any child has focus. */
fun Modifier.remembersGroupFocus(memory: FocusMemory, key: String): Modifier =
    focusRequester(memory.requester(key)).onFocusChanged { if (it.hasFocus) memory.onFocused(key) }

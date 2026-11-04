package app.antresol.tv.ui.components

import androidx.compose.runtime.Composable
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

    /** Focuses the remembered element, or [fallbackKey] when there is none or it is gone. */
    suspend fun restore(fallbackKey: String): Boolean {
        val key = focusedKey
        if (key != null && key != fallbackKey && requester(key).tryRequestFocus()) return true
        return requester(fallbackKey).tryRequestFocus()
    }

    companion object {
        val Saver: Saver<FocusMemory, String> = Saver(
            save = { it.focusedKey ?: "" },
            restore = { FocusMemory(it.ifEmpty { null }) },
        )
    }
}

@Composable
fun rememberFocusMemory(): FocusMemory = rememberSaveable(saver = FocusMemory.Saver) { FocusMemory(null) }

/** Registers this focusable under [key] in [memory]. Put it before the focusable modifier. */
fun Modifier.remembersFocus(memory: FocusMemory, key: String): Modifier =
    focusRequester(memory.requester(key)).onFocusChanged { if (it.isFocused) memory.onFocused(key) }

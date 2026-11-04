package app.antresol.tv.ui.components

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Requests focus once the target is attached. Lazy rows attach their items during layout, which can
 * run a frame after the effect that asks for focus, so retry for a few frames. Returns success.
 */
suspend fun FocusRequester.tryRequestFocus(frames: Int = 4): Boolean {
    repeat(frames) {
        withFrameNanos { }
        try {
            requestFocus()
            return true
        } catch (_: IllegalStateException) {
            // Not attached yet.
        }
    }
    return false
}

/**
 * Keeps D-pad Left/Right inside this group (a row of cards or buttons): at either end focus stays
 * put instead of jumping to whatever lies beyond, such as the top navigation.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.trapHorizontalFocus(): Modifier = focusProperties {
    exit = { direction ->
        if (direction == FocusDirection.Left || direction == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default
    }
}

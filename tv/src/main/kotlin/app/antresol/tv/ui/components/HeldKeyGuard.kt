package app.antresol.tv.ui.components

import android.os.SystemClock
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type

/**
 * Swallows the rest of a held OK after a long press opened something. A long press fires while
 * OK is still down; the key keeps repeating and is released later, and by then focus can be on
 * the page that opened (a Continue Watching card's details), whose focused Play button would take
 * the release as a click and start playback. Until the release, OK goes nowhere once focus has
 * left the element that was long-pressed; while that element still has focus it gets the keys
 * itself, so it sees its own release.
 */
object HeldKeyGuard {
    private var stillOnOwner: (() -> Boolean)? = null
    private var armedAt = 0L

    /** Called by a long press; [stillOnOwner] tells whether the long-pressed element still has focus. */
    fun arm(stillOnOwner: () -> Boolean) {
        this.stillOnOwner = stillOnOwner
        armedAt = SystemClock.uptimeMillis()
    }

    /** For a preview key handler at the root: true when [event] belongs to a held OK that must not reach the new focus. */
    fun consume(event: KeyEvent): Boolean {
        val owner = stillOnOwner ?: return false
        if (event.nativeKeyEvent.keyCode !in OK_KEYS) return false
        if (SystemClock.uptimeMillis() - armedAt > TIMEOUT_MS) {
            stillOnOwner = null
            return false
        }
        val release = event.type == KeyEventType.KeyUp
        if (release) stillOnOwner = null
        return !owner()
    }

    private val OK_KEYS = setOf(NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER)

    /** A release this late is not part of the same press (a lost key-up). */
    private const val TIMEOUT_MS = 10_000L
}

package app.antresol.tv.ui.components

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The held OK of a long press: what the guard swallows and when it lets go. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HeldKeyGuardTest {
    private fun down(repeat: Int) = KeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_CENTER, repeat))
    private fun up() = KeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_DPAD_CENTER))

    /** The guard is one per process: a new press ends whatever an earlier test left. */
    @Before
    fun letGo() {
        HeldKeyGuard.consume(down(0))
    }

    @Test
    fun theRestOfTheHeldKeyIsSwallowedOnceFocusLeftTheCard() {
        var onCard = true
        HeldKeyGuard.arm { onCard }
        assertFalse("the card gets its own repeats", HeldKeyGuard.consume(down(2)))
        onCard = false
        assertTrue(HeldKeyGuard.consume(down(3)))
        assertTrue("the release is swallowed too", HeldKeyGuard.consume(up()))
        assertFalse("and ends the guard", HeldKeyGuard.consume(up()))
    }

    /** The release came while the opened page had nothing focused, so the guard never saw it. */
    @Test
    fun aNewPressAfterALostReleaseIsNotSwallowed() {
        HeldKeyGuard.arm { false }
        assertTrue(HeldKeyGuard.consume(down(4)))
        assertFalse("the next press is the user's", HeldKeyGuard.consume(down(0)))
        assertFalse(HeldKeyGuard.consume(up()))
    }

    @Test
    fun otherKeysPass() {
        HeldKeyGuard.arm { false }
        assertFalse(HeldKeyGuard.consume(KeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_RIGHT, 3))))
        assertTrue(HeldKeyGuard.consume(down(5)))
    }
}

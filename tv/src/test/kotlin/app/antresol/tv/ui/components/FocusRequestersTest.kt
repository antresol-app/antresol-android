package app.antresol.tv.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Focus requesters are kept for the elements on screen only, and focus restoration still finds them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FocusRequestersTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aRequesterIsDroppedWhenItsElementLeaves() {
        val memory = FocusMemory(null)
        var shown by mutableStateOf(listOf("a", "b", "c"))
        compose.setContent {
            Column { shown.forEach { key -> Box(Modifier.size(20.dp).remembersFocus(memory, key).focusable().testTag(key)) } }
        }
        compose.waitForIdle()
        assertEquals(3, memory.requesters.size)
        val b = memory.requester("b")

        shown = listOf("b")
        compose.waitForIdle()
        assertEquals(1, memory.requesters.size)
        assertSame(b, memory.requester("b"))

        shown = emptyList()
        compose.waitForIdle()
        assertEquals(0, memory.requesters.size)
    }

    @Test
    fun aRequesterAskedForBeforeItsElementIsTheOneTheElementGets() {
        val memory = FocusMemory(null)
        var shown by mutableStateOf(false)
        compose.setContent {
            if (shown) Box(Modifier.size(20.dp).remembersFocus(memory, "late").focusable().testTag("late"))
        }
        val early = memory.requester("late") // focus restoration waiting for the card
        shown = true
        compose.waitForIdle()
        compose.runOnUiThread { early.requestFocus() }
        compose.onNodeWithTag("late").assertIsFocused()
        assertEquals("late", memory.focusedKey)
    }

    @Test
    fun anElementMovingToAnotherKeyLeavesNothingBehind() {
        val requesters = FocusRequesters<String>()
        var key by mutableStateOf("one")
        compose.setContent { Box(Modifier.size(20.dp).focusRequester(requesters, key).focusable()) }
        compose.waitForIdle()
        val one = requesters["one"]
        key = "two"
        compose.waitForIdle()
        assertEquals(1, requesters.size)
        assertNotSame(one, requesters["two"])
    }

    /** A focus request by key waiting for [clock]'s next frame; the result once it is over. */
    private fun request(requesters: FocusRequesters<String>, clock: BroadcastFrameClock, result: (Boolean) -> Unit): Job {
        lateinit var job: Job
        compose.runOnUiThread {
            job = CoroutineScope(clock + Dispatchers.Unconfined).launch { result(requesters.tryRequestFocus("k", frames = 1)) }
        }
        return job
    }

    @Test
    fun aFocusRequestGoesToTheRequesterTheElementCameBackWith() {
        val requesters = FocusRequesters<String>()
        val own = FocusRequester() // the element's, from when it was composed; its entry was dropped when it left
        var shown by mutableStateOf(false)
        compose.setContent {
            if (shown) {
                Box(Modifier.size(20.dp).focusRequester(own).focusable().testTag("k"))
                DisposableEffect(Unit) {
                    requesters.attached("k", own)
                    onDispose { requesters.detached("k", own) }
                }
            }
        }
        val clock = BroadcastFrameClock()
        var focused: Boolean? = null
        request(requesters, clock) { focused = it }
        // The element comes back, with its own requester, while the request waits for its frame.
        shown = true
        compose.waitForIdle()
        compose.runOnUiThread { clock.sendFrame(0L) }
        assertEquals(true, focused)
        compose.onNodeWithTag("k").assertIsFocused()
        assertSame(own, requesters["k"])
        assertEquals(1, requesters.size)
    }

    @Test
    fun aFocusRequestCancelledWhileItWaitsLeavesNothingBehind() {
        val requesters = FocusRequesters<String>()
        val clock = BroadcastFrameClock()
        val job = request(requesters, clock) { }
        compose.runOnUiThread { job.cancel() }
        assertEquals(0, requesters.size)
        // Nor does one that waited in vain.
        var focused: Boolean? = null
        request(requesters, clock) { focused = it }
        compose.runOnUiThread { clock.sendFrame(0L) }
        assertEquals(false, focused)
        assertEquals(0, requesters.size)
    }

    @Test
    fun aFocusRequestForAnElementThatNeverCameLeavesNothingBehind() {
        val requesters = FocusRequesters<String>()
        assertEquals(false, requesters.requestFocus("gone"))
        assertEquals(0, requesters.size)
    }

    @Test
    fun twoElementsOfOneKeyKeepTheRequesterUntilBothLeft() {
        val requesters = FocusRequesters<String>()
        val shared = requesters["k"]
        requesters.attached("k", shared)
        requesters.attached("k", shared)
        requesters.detached("k", shared)
        assertSame(shared, requesters["k"])
        requesters.detached("k", shared)
        assertEquals(0, requesters.size)
    }
}

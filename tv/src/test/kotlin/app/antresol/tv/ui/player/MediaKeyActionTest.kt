package app.antresol.tv.ui.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaKeyActionTest {
    @Test
    fun stopLeavesAndPreviousRestarts() {
        assertEquals(MediaKeyAction.Stop, mediaKeyAction(Key.MediaStop))
        assertEquals(MediaKeyAction.Restart, mediaKeyAction(Key.MediaPrevious))
    }

    @Test
    fun theKeysOfBeforeDoWhatTheyDid() {
        assertEquals(MediaKeyAction.TogglePlay, mediaKeyAction(Key.MediaPlayPause))
        assertEquals(MediaKeyAction.Play, mediaKeyAction(Key.MediaPlay))
        assertEquals(MediaKeyAction.Pause, mediaKeyAction(Key.MediaPause))
        assertEquals(MediaKeyAction.Next, mediaKeyAction(Key.MediaNext))
        for (key in listOf(Key.MediaFastForward, Key.MediaSkipForward, Key.MediaStepForward)) {
            assertEquals(MediaKeyAction.Forward, mediaKeyAction(key))
        }
        for (key in listOf(Key.MediaRewind, Key.MediaSkipBackward, Key.MediaStepBackward)) {
            assertEquals(MediaKeyAction.Backward, mediaKeyAction(key))
        }
    }

    @Test
    fun otherKeysAreNotMediaKeys() {
        assertNull(mediaKeyAction(Key.DirectionCenter))
        assertNull(mediaKeyAction(Key.Back))
    }
}

package app.antresol.mobile.ui.detail

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.mobile.R
import app.antresol.mobile.ui.playlists.PlaylistNotice
import app.antresol.mobile.ui.playlists.PlaylistNoticeSnackbar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A notice is shown as a snackbar although showing it clears it (a failed toggle said nothing before). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class NoticeSnackbarTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aNoticeStaysOnScreenAfterItWasTakenFromThePage() {
        val host = SnackbarHostState()
        var notice by mutableStateOf<Int?>(null)
        compose.setContent {
            NoticeSnackbar(notice, host) { notice = null }
            SnackbarHost(host)
        }
        notice = R.string.detail_action_failed
        compose.waitForIdle()
        assertEquals(null, notice)
        val text = compose.activity.getString(R.string.detail_action_failed)
        assertEquals(text, host.currentSnackbarData?.visuals?.message)
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun aPlaylistNoticeToo() {
        val host = SnackbarHostState()
        var notice by mutableStateOf<PlaylistNotice?>(null)
        compose.setContent {
            PlaylistNoticeSnackbar(notice, host) { notice = null }
            SnackbarHost(host)
        }
        notice = PlaylistNotice(R.string.detail_action_failed)
        compose.waitForIdle()
        assertEquals(compose.activity.getString(R.string.detail_action_failed), host.currentSnackbarData?.visuals?.message)
    }
}

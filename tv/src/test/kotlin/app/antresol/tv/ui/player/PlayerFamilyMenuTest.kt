package app.antresol.tv.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.family.FamilyKind
import app.antresol.core.model.FamilyMember
import app.antresol.tv.AntresolTvTheme
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.tv.ui.detail.FAMILY_PANEL_WIDTH
import app.antresol.ui.family.familyMenuOptions
import app.antresol.ui.playback.PlayerMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * «Семья» in the film player: a button joins the control row only when somebody else is somewhere in what plays; it opens
 * the list of who is where, and OK on a row asks to continue from their place (the player copies it, then seeks).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerFamilyMenuTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shots = File("build/screenshots").apply { mkdirs() }
    private val jumped = ArrayList<String>()

    private val sam = FamilyMember(
        userId = 2, name = "Sam", itemId = 10, positionMs = 34 * 60_000, durationMs = 104 * 60_000, fraction = 0.33,
        updatedAt = java.time.Instant.now().minusSeconds(2 * 3600).toString(),
    )
    private val robin = sam.copy(userId = 3, name = "Robin", positionMs = 70 * 60_000, fraction = 0.67, updatedAt = java.time.Instant.now().minusSeconds(26 * 3600).toString())

    private fun show(members: List<FamilyMember>) {
        compose.setContent {
            AntresolTvTheme {
                val resources = LocalContext.current.resources
                PlayingOverlayContent(
                    transport = TransportState(
                        positionMs = { 754_000 }, bufferedMs = { 900_000 }, durationMs = 6_240_000, playing = true,
                        audioChoice = false, subtitleChoice = false, nextEpisode = false, qualityChoice = false,
                        familyChoice = members.isNotEmpty(),
                    ),
                    isPlaying = false,
                    busy = false,
                    title = {},
                    actions = TransportActions(
                        onSeek = {}, onToggle = {}, onNext = {},
                        onScrubKey = { false }, onMediaKey = { false }, onHiddenKey = { _, _ -> false },
                    ),
                    menuContent = { menu, onDismiss ->
                        assertEquals(PlayerMenu.Family, menu)
                        OptionsPanel(
                            title = "Family",
                            options = familyMenuOptions(resources, FamilyKind.Movie, members, emptyList(), photoUrl = { null }, onJump = { jumped += it.name }),
                            onDismiss = onDismiss,
                            width = FAMILY_PANEL_WIDTH,
                        )
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theButtonOpensTheListAndOkOnARowContinuesFromThere() {
        show(listOf(sam, robin))
        key(KeyEvent.KEYCODE_DPAD_DOWN) // the seek bar → Play
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val button = compose.onNode(hasContentDescription("Family"))
        button.assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Play from 34:00").assertExists()
        compose.onNodeWithText("Play from 1:10:00").assertExists()
        compose.onNode(hasText("34 min of 1 h 44 min", substring = true)).assertExists()
        shot("family-tv-player-menu")
        // Down to the second row, OK: continue from Robin's place; the menu closes and the button has focus again.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("Robin"), jumped)
        compose.onNodeWithText("Play from 34:00").assertDoesNotExist()
        compose.onNode(isFocused() and hasContentDescription("Family")).assertExists()
    }

    @Test
    fun noButtonWhenNobodyElseIsInThisItem() {
        show(emptyList())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(compose.onAllNodes(hasContentDescription("Family")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(PlayerTags.PLAY_PAUSE).assertIsFocused()
    }

    private fun key(code: Int, times: Int = 1) = repeat(times) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

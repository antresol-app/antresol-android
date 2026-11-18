package app.antresol.mobile.player

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.playback.SkipAction
import app.antresol.core.playback.SkipNote
import app.antresol.core.playback.SkipPill
import app.antresol.ui.theme.AntresolTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The phone player's skip pills: tappable «Пропустить вступление» (hidden past its 8 s unless the
 * controls show), «Смотреть титры» + «Следующая серия» at the credits, «Вернуть» after an automatic
 * skip. Screenshots go to `mobile/build/screenshots/skip-*.png`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "ru-w780dp-h360dp-land-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkipPillsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shots = File("build/screenshots").apply { mkdirs() }
    private var pill by mutableStateOf<SkipPill?>(null)
    private var note by mutableStateOf<SkipNote?>(null)
    private var controls by mutableStateOf(false)
    private var presses = 0
    private var watchCredits = 0
    private var undos = 0

    private fun show() {
        compose.setContent {
            AntresolTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF3A4A5A))) {
                    SkipPills(
                        pill = pill,
                        note = note,
                        controlsVisible = controls,
                        onPress = { presses++ },
                        onWatchCredits = { watchCredits++ },
                        onUndo = { undos++ },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun skipIntroTapsAndHidesPastItsEightSeconds() {
        pill = SkipPill.Skip("intro", alone = true, action = SkipAction.Seek(150_000))
        show()
        shot("skip-intro")
        compose.onNodeWithText("Пропустить вступление").performClick()
        assertEquals(1, presses)

        pill = SkipPill.Skip("intro", alone = false, action = SkipAction.Seek(150_000))
        compose.waitForIdle()
        compose.onNodeWithTag(SkipTags.SKIP).assertDoesNotExist()
        controls = true
        compose.waitForIdle()
        compose.onNodeWithTag(SkipTags.SKIP).assertExists()
    }

    @Test
    fun nextEpisodeAndWatchCredits() {
        pill = SkipPill.Next(fill = 0.4f, countdown = true)
        show()
        shot("skip-next")
        compose.onNodeWithText("Следующая серия").performClick()
        compose.onNodeWithText("Смотреть титры").performClick()
        assertEquals(1, presses)
        assertEquals(1, watchCredits)
    }

    @Test
    fun undoAfterAnAutomaticSkip() {
        note = SkipNote("recap", backMs = 0)
        show()
        compose.onNodeWithText("Пересказ пропущен").assertExists()
        shot("skip-note")
        compose.onNodeWithText("Вернуть").performClick()
        assertEquals(1, undos)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

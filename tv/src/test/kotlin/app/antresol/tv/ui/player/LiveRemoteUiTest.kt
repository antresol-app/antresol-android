package app.antresol.tv.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.core.channels.Channels
import app.antresol.core.model.Channel
import app.antresol.tv.AntresolTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The live player's remote parts as TiviMate has them: the typed number top right («23_» with the
 * channel it will switch to) and the category column beside the channel panel. With
 * ANTRESOL_SHOTS_DIR set, the frames are written there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveRemoteUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val channels = listOf("News 24", "Documentaries", "Films", "Comedy", "Kids", "Music", "Sport").mapIndexed { i, name ->
        Channel(id = i + 1L, number = 21 + i, name = name, category = "")
    }

    private fun picture() = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B3A5C), Color(0xFFB88A5A), Color(0xFF173247))))

    private fun capture(name: String) {
        val dir = System.getenv("ANTRESOL_SHOTS_DIR") ?: return
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theTypedNumberShowsTopRightWithItsChannel() {
        compose.setContent {
            AntresolTvTheme {
                Box(picture()) {
                    DigitsOverlay("23_", channels[2], Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 48.dp))
                }
            }
        }
        compose.onNodeWithText("23_").assertExists()
        compose.onNodeWithText("Films").assertExists()
        capture("tv-digits")
    }

    @Test
    fun theCategoryColumnSitsBesideTheChannels() {
        val entries = listOf(
            CategoryEntry("Favourites", 3, false), CategoryEntry("All Channels", 7, true),
            CategoryEntry("News", 1, false), CategoryEntry("Documentaries", 2, false), CategoryEntry("Kids", 1, false), CategoryEntry("For adults", 1, false),
        )
        compose.setContent {
            AntresolTvTheme {
                Box(picture()) {
                    CategoryColumn(entries, selected = 3) {
                        ChannelListPanel(Channels.miniGuide(channels, 2), "Documentaries", currentId = 2, selected = -1, startPadding = 12.dp)
                    }
                }
            }
        }
        compose.onNodeWithTag("category-column").assertExists()
        capture("tv-category-column")
    }
}

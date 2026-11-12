package app.antresol.mobile.ui.shell

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The tab bar on the narrowest phone: every label shows in full (no «…») and neighbouring labels
 * keep a visible gap (owner's round-4 shot had «Фильмы» touching «Сериа…» at 360 dp).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomBarTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun labelsFitAndKeepApart(name: String) {
        compose.setContent {
            AntresolTheme {
                Box(Modifier.fillMaxSize().background(AntresolColors.Background)) {
                    BottomBar(selected = NavTab.entries.first(), onSelect = {}, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        val labels = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.left }
        assertEquals("one label per tab except Search", NavTab.entries.size - 1, labels.size)
        labels.forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val layout = results.single()
            val text = node.config[SemanticsProperties.Text].joinToString()
            val need = layout.multiParagraph.intrinsics.maxIntrinsicWidth / density
            val have = layout.layoutInput.constraints.maxWidth / density
            assertFalse("«$text» is cut short: needs $need dp, has $have dp", layout.isLineEllipsized(0) || need > have + 0.5f)
        }
        labels.zipWithNext().forEach { (a, b) ->
            val gap = (b.boundsInRoot.left - a.boundsInRoot.right) / density
            assertTrue("labels ${a.config[SemanticsProperties.Text]} and ${b.config[SemanticsProperties.Text]} are $gap dp apart", gap >= 4f)
        }
        capture(name)
    }

    private fun capture(name: String) {
        val dir = System.getenv("ANTRESOL_SHOTS_DIR") ?: return
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-port")
    fun russianLabelsFitA360dpPhone() = labelsFitAndKeepApart("robolectric-bottom-bar-360dp-ru")

    @Test
    @Config(sdk = [35], qualifiers = "en-w360dp-h640dp-port")
    fun englishLabelsFitA360dpPhone() = labelsFitAndKeepApart("robolectric-bottom-bar-360dp-en")
}

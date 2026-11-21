package app.antresol.tv.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.tv.AntresolTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The 960×540 dp design canvas maps to the real window, whatever density the TV reports. */
class CanvasDensityTest {
    private val reported = Density(1.3312f, fontScale = 1f)

    @Test
    fun aFullHdWindowGetsTheDesignDensityOfTwo() {
        assertEquals(2f, canvasDensity(1920, 1080, reported).density, 0.0001f)
    }

    @Test
    fun aFourKWindowDoublesIt() {
        assertEquals(4f, canvasDensity(3840, 2160, reported).density, 0.0001f)
    }

    @Test
    fun anHdWindowShrinksIt() {
        assertEquals(1.3333f, canvasDensity(1280, 720, reported).density, 0.0001f)
    }

    @Test
    fun aWiderScreenFitsTheHeightAndLeavesTheCanvasWider() {
        // 21:9: 2560x1080 is 1280 dp wide and 540 dp tall.
        assertEquals(2f, canvasDensity(2560, 1080, reported).density, 0.0001f)
    }

    @Test
    fun aTallerScreenFitsTheWidth() {
        // 4:3: 1440x1080 is 960 dp wide and 720 dp tall.
        assertEquals(1.5f, canvasDensity(1440, 1080, reported).density, 0.0001f)
    }

    @Test
    fun theFontScaleOfTheSystemIsKept() {
        assertEquals(1.15f, canvasDensity(1920, 1080, Density(1.3312f, fontScale = 1.15f)).fontScale, 0.0001f)
    }

    @Test
    fun aWindowNotMeasuredYetLeavesTheDensityAlone() {
        assertEquals(reported, canvasDensity(0, 0, reported))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w1442dp-h811dp-land-television-tvdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignCanvasTvdpiTest : DesignCanvasBase()

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignCanvasXhdpiTest : DesignCanvasBase()

abstract class DesignCanvasBase {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theWindowIsNineHundredSixtyCanvasDpWide() {
        compose.setContent { AntresolTvTheme { Box(Modifier.width(CanvasWidth).height(CanvasHeight).testTag("canvas")) } }
        compose.waitForIdle()
        val window = compose.activity.window.decorView
        val box = compose.onNodeWithTag("canvas").fetchSemanticsNode().size
        // The window is 1920×1080 px at either density (a px of rounding at tvdpi).
        assertEquals("canvas width in px", window.width.toFloat(), box.width.toFloat(), 2f)
        assertEquals("canvas height in px", window.height.toFloat(), box.height.toFloat(), 2f)
        assertEquals(1920f, window.width.toFloat(), 2f)
    }

    @Test
    fun theHeightInCanvasDpIsTheDesignsFiveHundredForty() {
        var height by mutableStateOf(0.dp)
        compose.setContent { AntresolTvTheme { height = canvasHeight() } }
        compose.waitForIdle()
        assertEquals(540f, height.value, 0.5f)
    }
}

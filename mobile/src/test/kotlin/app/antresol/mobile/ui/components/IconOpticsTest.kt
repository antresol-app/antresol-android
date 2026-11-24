package app.antresol.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.IconOptics
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot

/** Where the ink of an icon sits on its 24-unit grid: its bounding box and its centre of mass. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconOpticsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Ink(val boxX: Double, val boxY: Double, val massX: Double, val massY: Double)

    /** Draws every icon on a black 120 dp cell (5 px per grid unit at mdpi) and measures each cell. */
    private fun measureAll(icons: List<ImageVector>): List<Ink> {
        val columns = 6
        compose.setContent {
            Column {
                icons.chunked(columns).forEach { row ->
                    Row {
                        row.forEach { icon ->
                            Box(Modifier.size(CELL.dp).background(Color.Black)) {
                                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(CELL.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val unit = CELL / 24.0
        return icons.indices.map { index ->
            val ox = (index % columns) * CELL
            val oy = (index / columns) * CELL
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
            var sum = 0.0; var sx = 0.0; var sy = 0.0
            for (y in 0 until CELL) for (x in 0 until CELL) {
                val w = (bitmap.getPixel(ox + x, oy + y) and 0xFF) / 255.0
                if (w > 0.2) { minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
                sum += w; sx += w * (x + 0.5); sy += w * (y + 0.5)
            }
            Ink((minX + maxX + 1) / 2.0 / unit, (minY + maxY + 1) / 2.0 / unit, sx / sum / unit, sy / sum / unit)
        }
    }

    /** The icons of the round buttons of the player overlays and the detail action row. */
    private val icons = listOf(
        AntresolIcons.Play, AntresolIcons.Pause, AntresolIcons.Audio, AntresolIcons.Subtitles, AntresolIcons.Sliders,
        AntresolIcons.SkipAhead, AntresolIcons.SkipNext, AntresolIcons.Restart, AntresolIcons.Trailer, AntresolIcons.ChevronDown,
        MobileIcons.Pip, MobileIcons.ChevronLeft, MobileIcons.Close, AntresolIcons.List, AntresolIcons.Star,
    )

    @Test
    fun theTableFollowsTheInk() {
        val problems = mutableListOf<String>()
        val report = StringBuilder()
        icons.zip(measureAll(icons)).forEach { (icon, ink) ->
            val shift = IconOptics.shift(icon)
            report.append(String.format("%-12s ink box=(%.2f, %.2f) mass=(%.2f, %.2f) shift=(%.2f, %.2f)\n", icon.name, ink.boxX, ink.boxY, ink.massX, ink.massY, shift.x, shift.y))
            for ((axis, box, mass, applied) in listOf(
                Axis("x", ink.boxX, ink.massX, shift.x.toDouble()),
                Axis("y", ink.boxY, ink.massY, shift.y.toDouble()),
            )) {
                if (abs(box - 12.0) > FRAME_CENTRED) {
                    // Drawn toward its tip by hand: left alone.
                    if (applied != 0.0) problems += "${icon.name} $axis: the frame is off by design, no shift expected, got $applied"
                    continue
                }
                val expected = 0.6 * (12.0 - mass)
                val tolerance = if (abs(expected) >= 0.4) 0.15 else 0.3
                if (abs(applied - expected) > tolerance) problems += "${icon.name} $axis: ink says %.2f, table has %.2f".format(expected, applied)
            }
        }
        java.io.File("build/ink.txt").writeText(report.toString())
        assertTrue(problems.joinToString("\n") + "\n" + report, problems.isEmpty())
    }

    /** What the offset does: the ink of the shifted icon is nearer the middle than the ink of the plain one. */
    @Test
    fun theShiftBringsTheInkTowardTheMiddle() {
        val ink = icons.zip(measureAll(icons)).toMap()
        for ((name, shift) in IconOptics.table) {
            val icon = icons.first { it.name == name }
            val measured = ink.getValue(icon)
            val before = hypot(measured.massX - 12.0, measured.massY - 12.0)
            val after = hypot(measured.massX + shift.x - 12.0, measured.massY + shift.y - 12.0)
            assertTrue("$name: $before -> $after", after < before)
        }
    }

    private data class Axis(val name: String, val box: Double, val mass: Double, val applied: Double)

    private companion object {
        const val CELL = 120
        const val FRAME_CENTRED = 0.6
    }
}

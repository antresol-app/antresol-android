package app.antresol.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import app.antresol.core.model.WayIcon

/** The icon an addon's way asks for by name; a name the app does not know is a play triangle. */
fun wayIcon(name: String): ImageVector = when (name) {
    WayIcon.DOWNLOAD -> WayIcons.Download
    WayIcon.GLOBE -> WayIcons.Globe
    WayIcon.LIST -> AntresolIcons.List
    else -> AntresolIcons.Play
}

/** The icons of ways that the shared set does not have, drawn like it (a line, tinted by the user). */
object WayIcons {
    /** An arrow into a tray. */
    val Download: ImageVector = lineIcon("Download") {
        moveTo(12f, 4f)
        lineTo(12f, 15f)
        moveTo(7.5f, 10.5f)
        lineTo(12f, 15f)
        lineTo(16.5f, 10.5f)
        moveTo(5f, 19f)
        lineTo(19f, 19f)
    }

    /** A globe: a circle, the equator and a meridian. */
    val Globe: ImageVector = lineIcon("Globe") {
        moveTo(21f, 12f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 3f, y1 = 12f)
        arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 21f, y1 = 12f)
        moveTo(3f, 12f)
        lineTo(21f, 12f)
        moveTo(12f, 3f)
        curveTo(8.5f, 7f, 8.5f, 17f, 12f, 21f)
        moveTo(12f, 3f)
        curveTo(15.5f, 7f, 15.5f, 17f, 12f, 21f)
    }

    private fun lineIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()
}
